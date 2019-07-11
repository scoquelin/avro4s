package com.sksamuel.avro4s

import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID

import magnolia.{CaseClass, Magnolia, SealedTrait}
import org.apache.avro.{JsonProperties, LogicalTypes, Schema, SchemaBuilder}
import shapeless.{:+:, CNil, Coproduct}

import scala.language.experimental.macros
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._
import scala.util.control.NonFatal


/**
  * A [[SchemaFor]] generates an Avro Schema for a Scala or Java type.
  *
  * For example, a String SchemaFor could return an instance of Schema.Type.STRING
  * or Schema.Type.FIXED depending on the type required for Strings.
  */
trait SchemaFor[T] extends Serializable {
  self =>

  def schema(namingStrategy: NamingStrategy): Schema

  /**
    * Creates a SchemaFor[U] by applying a function Schema => Schema
    * to the schema generated by this instance.
    */
  def map[U](fn: Schema => Schema): SchemaFor[U] = new SchemaFor[U] {
    override def schema(namingStrategy: NamingStrategy): Schema = fn(self.schema(namingStrategy))
  }
}

case class ScalePrecision(scale: Int, precision: Int)

object ScalePrecision {
  implicit val default = ScalePrecision(2, 8)
}

object SchemaFor {

  import scala.collection.JavaConverters._

  type Typeclass[T] = SchemaFor[T]

  def apply[T](implicit schemaFor: SchemaFor[T]): SchemaFor[T] = schemaFor

  /**
    * Creates a [[SchemaFor]] that always returns the given constant value.
    */
  def const[T](_schema: Schema) = new SchemaFor[T] {
    override def schema(namingStrategy: NamingStrategy) = _schema
  }

  implicit val StringSchemaFor: SchemaFor[String] = const(SchemaBuilder.builder.stringType)
  implicit val LongSchemaFor: SchemaFor[Long] = const(SchemaBuilder.builder.longType)
  implicit val IntSchemaFor: SchemaFor[Int] = const(SchemaBuilder.builder.intType)
  implicit val DoubleSchemaFor: SchemaFor[Double] = const(SchemaBuilder.builder.doubleType)
  implicit val FloatSchemaFor: SchemaFor[Float] = const(SchemaBuilder.builder.floatType)
  implicit val BooleanSchemaFor: SchemaFor[Boolean] = const(SchemaBuilder.builder.booleanType)
  implicit val ByteArraySchemaFor: SchemaFor[Array[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteSeqSchemaFor: SchemaFor[Seq[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteListSchemaFor: SchemaFor[List[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteVectorSchemaFor: SchemaFor[Vector[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteBufferSchemaFor: SchemaFor[ByteBuffer] = const(SchemaBuilder.builder.bytesType)
  implicit val ShortSchemaFor: SchemaFor[Short] = const(IntSchemaFor.schema(DefaultNamingStrategy))
  implicit val ByteSchemaFor: SchemaFor[Byte] = const(IntSchemaFor.schema(DefaultNamingStrategy))

  implicit object UUIDSchemaFor extends SchemaFor[UUID] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.uuid().addToSchema(SchemaBuilder.builder.stringType)
  }

  implicit def mapSchemaFor[V](implicit schemaFor: SchemaFor[V]): SchemaFor[Map[String, V]] = {
    new SchemaFor[Map[String, V]] {
      override def schema(namingStrategy: NamingStrategy) = SchemaBuilder.map().values(schemaFor.schema(namingStrategy))
    }
  }

  implicit def bigDecimalFor(implicit sp: ScalePrecision = ScalePrecision.default): SchemaFor[BigDecimal] = new SchemaFor[BigDecimal] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.decimal(sp.precision, sp.scale).addToSchema(SchemaBuilder.builder.bytesType)
  }

  implicit def eitherSchemaFor[A, B](implicit leftFor: SchemaFor[A], rightFor: SchemaFor[B]): SchemaFor[Either[A, B]] = {
    new SchemaFor[Either[A, B]] {
      override def schema(namingStrategy: NamingStrategy) = SchemaHelper.createSafeUnion(leftFor.schema(namingStrategy), rightFor.schema(namingStrategy))
    }
  }

  implicit def optionSchemaFor[T](implicit schemaFor: SchemaFor[T]): SchemaFor[Option[T]] = new SchemaFor[Option[T]] {
    override def schema(namingStrategy: NamingStrategy) = {
      val elementSchema = schemaFor.schema(namingStrategy)
      val nullSchema = SchemaBuilder.builder().nullType()
      SchemaHelper.createSafeUnion(elementSchema, nullSchema)
    }
  }

  implicit def arraySchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Array[S]] = {
    new SchemaFor[Array[S]] {
      override def schema(namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema(namingStrategy))
    }
  }

  implicit def listSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[List[S]] = {
    new SchemaFor[List[S]] {
      override def schema(namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema(namingStrategy))
    }
  }

  implicit def setSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Set[S]] = {
    new SchemaFor[Set[S]] {
      override def schema(namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema(namingStrategy))
    }
  }

  implicit def vectorSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Vector[S]] = {
    new SchemaFor[Vector[S]] {
      override def schema(namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema(namingStrategy))
    }
  }

  implicit def seqSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Seq[S]] = {
    new SchemaFor[Seq[S]] {
      override def schema(namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema(namingStrategy))
    }
  }

  implicit def iterableSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Iterable[S]] = {
    new SchemaFor[Iterable[S]] {
      override def schema(namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema(namingStrategy))
    }
  }

  implicit object TimestampSchemaFor extends SchemaFor[Timestamp] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder.longType)
  }

  implicit object LocalTimeSchemaFor extends SchemaFor[LocalTime] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.timeMillis().addToSchema(SchemaBuilder.builder.intType)
  }

  implicit object LocalDateSchemaFor extends SchemaFor[LocalDate] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.date().addToSchema(SchemaBuilder.builder.intType)
  }

  implicit object LocalDateTimeSchemaFor extends SchemaFor[LocalDateTime] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder.longType)
  }

  implicit object DateSchemaFor extends SchemaFor[java.sql.Date] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.date().addToSchema(SchemaBuilder.builder.intType)
  }

  implicit object InstantSchemaFor extends SchemaFor[Instant] {
    override def schema(namingStrategy: NamingStrategy) = LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder.longType)
  }

  implicit def javaEnumSchemaFor[E <: Enum[_]](implicit tag: ClassTag[E]): SchemaFor[E] = new SchemaFor[E] {
    override def schema(namingStrategy: NamingStrategy): Schema = {

      val as = tag.runtimeClass.getAnnotations
      val nameAnnotation = as.find(_.annotationType == classOf[AvroName]).map(_.asInstanceOf[AvroName]).map(_.name)
      val namespaceAnnotation = as.find(_.annotationType == classOf[AvroNamespace]).map(_.asInstanceOf[AvroNamespace]).map(_.namespace)
      val namer = Namer(magnolia.TypeName(tag.runtimeClass.getPackage.getName, tag.runtimeClass.getSimpleName, Nil), nameAnnotation, namespaceAnnotation, false)
      val symbols = tag.runtimeClass.getEnumConstants.map(_.toString)

      SchemaBuilder.enumeration(namer.name).namespace(namer.namespace).symbols(symbols: _*)
    }
  }


  /**
    * Builds an Avro Field with the field's Schema provided by an
    * implicit instance of [[SchemaFor]]. There must be a instance of this
    * typeclass in scope for any type we want to support in avro4s.
    *
    * Users can add their own mappings for types by implementing a [[SchemaFor]]
    * instance for that type.
    *
    * @param label   the name of the field as defined in the case class
    * @param annos   the name of the package that contains the case class definition
    * @param default an instance of the Default ADT which contains an avro compatible default value
    *                if such a default applies to this field
    */
  private def buildField[B](label: String,
                            containingNamespace: String,
                            annos: Seq[Any],
                            fieldSchema: Schema,
                            default: Option[B],
                            namingStrategy: NamingStrategy,
                            valueTypeDoc: Option[String]): Schema.Field = {

    val extractor = new AnnotationExtractors(annos)
    val doc = extractor.doc.orElse(valueTypeDoc).orNull
    val aliases = extractor.aliases
    val props = extractor.props

    // the name could have been overriden with @AvroName, and then must be encoded with the naming strategy
    val name = extractor.name.fold(namingStrategy.to(label))(namingStrategy.to)

    // the default value may be none, in which case it was not defined, or Some(null), in which case it was defined
    // and set to null, or something else, in which case it's a non null value
    val encodedDefault: AnyRef = default match {
      case None => null
      case Some(None) => JsonProperties.NULL_VALUE
      case Some(null) => JsonProperties.NULL_VALUE
      case Some(other) => DefaultResolver(other, fieldSchema)
    }

    // if we have annotated with @AvroFixed then we override the type and change it to a Fixed schema
    // if someone puts @AvroFixed on a complex type, it makes no sense, but that's their cross to bear
    val schema = extractor.fixed.fold(fieldSchema) { size =>
      SchemaBuilder.fixed(name).doc(doc).namespace(extractor.namespace.getOrElse(containingNamespace)).size(size)
    }

    // if our default value is null, then we should change the type to be nullable even if we didn't use option
    val schemaWithPossibleNull = if (default.contains(null) && schema.getType != Schema.Type.UNION) {
      SchemaBuilder.unionOf().`type`(schema).and().`type`(Schema.create(Schema.Type.NULL)).endUnion()
    } else schema

    // for a union the type that has a default must be first (including null as an explicit default)
    // if there is no default then we'll move null to head (if present)
    // otherwise left as is
    val schemaWithOrderedUnion = (schemaWithPossibleNull.getType, encodedDefault) match {
      case (Schema.Type.UNION, null) => SchemaHelper.moveNullToHead(schemaWithPossibleNull)
      case (Schema.Type.UNION, JsonProperties.NULL_VALUE) => SchemaHelper.moveNullToHead(schemaWithPossibleNull)
      case (Schema.Type.UNION, defaultValue) => SchemaHelper.moveDefaultToHead(schemaWithPossibleNull, defaultValue)
      case _ => schemaWithPossibleNull
    }

    // the field can override the containingNamespace if the Namespace annotation is present on the field
    // we may have annotated our field with @AvroNamespace so this containingNamespace should be applied
    // to any schemas we have generated for this field
    val schemaWithResolvedNamespace = extractor.namespace
      .map(SchemaHelper.overrideNamespace(schemaWithOrderedUnion, _))
      .getOrElse(schemaWithOrderedUnion)

    val field = if (encodedDefault == null)
      new Schema.Field(name, schemaWithResolvedNamespace, doc)
    else
      new Schema.Field(name, schemaWithResolvedNamespace, doc, encodedDefault)

    props.foreach { case (k, v) => field.addProp(k, v: AnyRef) }
    aliases.foreach(field.addAlias)
    field
  }

  def combine[T](ctx: CaseClass[Typeclass, T]): SchemaFor[T] = new SchemaFor[T] {
    override def schema(namingStrategy: NamingStrategy) = {

      val extractor = new AnnotationExtractors(ctx.annotations)
      val doc = extractor.doc.orNull
      val aliases = extractor.aliases
      val props = extractor.props

      val namer = Namer(ctx.typeName, ctx.annotations)
      val namespace = namer.namespace
      val name = namer.name

      // if the class is a value type, then we need to use the schema for the single field inside the type
      // in other words, if we have `case class Foo(str:String)` then this just acts like a string
      // if we have a value type AND @AvroFixed is present on the class, then we simply return a schema of type fixed
      if (ctx.isValueClass) {
        val param = ctx.parameters.head
        extractor.fixed match {
          case Some(size) =>
            val builder = SchemaBuilder.fixed(name).doc(doc).namespace(namespace).aliases(aliases: _*)
            props.foreach { case (k, v) => builder.prop(k, v) }
            builder.size(size)
          case None => param.typeclass.schema(namingStrategy)
        }

      } else {

        val fields = ctx.parameters.flatMap { param =>
          if (new AnnotationExtractors(param.annotations).transient) None else {
            val s = param.typeclass.schema(namingStrategy)
            // if the field is a value type then we may have annotated it with @AvroDoc, and that doc should be
            // placed onto the field, not onto the record type, because there won't be a record type for a value type!
            // magnolia won't give us the type of the parameter, so we must find it in the class type
            val doc = try {
              import scala.reflect.runtime.universe
              val mirror = universe.runtimeMirror(getClass.getClassLoader)
              val sym = mirror.staticClass(ctx.typeName.full).primaryConstructor.asMethod.paramLists.head(param.index)
              sym.typeSignature.typeSymbol.annotations.collectFirst {
                case a if a.tree.tpe =:= typeOf[AvroDoc] =>
                  val annoValue = a.tree.children.tail.head.asInstanceOf[Literal].value.value
                  annoValue.toString
              }
            } catch {
              case NonFatal(_) => None
            }
            Some(buildField(param.label, namespace, param.annotations, s, param.default, namingStrategy, doc))
          }
        }

        val record = Schema.createRecord(name.replaceAll("[^a-zA-Z0-9_]", ""), doc, namespace.replaceAll("[^a-zA-Z0-9_.]", ""), false)
        aliases.foreach(record.addAlias)
        props.foreach { case (k, v) => record.addProp(k: String, v: AnyRef) }
        record.setFields(fields.asJava)

        record
      }
    }
  }

  def dispatch[T: WeakTypeTag](ctx: SealedTrait[Typeclass, T]): SchemaFor[T] = new SchemaFor[T] {
    override def schema(namingStrategy: NamingStrategy) = {

      import scala.reflect.runtime.universe

      val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
      val tpe = runtimeMirror.weakTypeOf[T]
      val objs = tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.knownDirectSubclasses.forall(_.isModuleClass)

      if (objs) {
        val symbols = ctx.subtypes.map { sub =>
          val namer = Namer(sub.typeName, sub.annotations)
          namer.name
        }
        val namer = Namer(ctx.typeName, ctx.annotations)
        SchemaBuilder.enumeration(namer.name).namespace(namer.namespace).symbols(symbols: _*)
      } else {
        val schemas = ctx.subtypes.map(_.typeclass.schema(namingStrategy))
        SchemaHelper.createSafeUnion(schemas: _*)
      }
    }
  }

  implicit def scalaEnumSchemaFor[E <: scala.Enumeration#Value](implicit tag: TypeTag[E]): SchemaFor[E] = new SchemaFor[E] {

    val typeRef = tag.tpe match {
      case t@TypeRef(_, _, _) => t
    }

    val valueType = typeOf[E]
    val pre = typeRef.pre.typeSymbol.typeSignature.members.sorted
    val syms = pre.filter { sym =>
      !sym.isMethod &&
        !sym.isType &&
        sym.typeSignature.baseType(valueType.typeSymbol) =:= valueType
    }.map { sym =>
      sym.name.decodedName.toString.trim
    }

    val as = typeRef.pre.typeSymbol.annotations
    val nameAnnotation = as.collectFirst {
      case a: AvroName => a.name
    }
    val namespaceAnnotation = as.collectFirst {
      case a: AvroNamespace => a.namespace
    }
    val props = as.collect {
      case prop: AvroProp => prop.key -> prop.value
    }
    val namer = Namer(magnolia.TypeName(typeRef.pre.typeSymbol.owner.fullName, typeRef.pre.typeSymbol.name.decodedName.toString, Nil), nameAnnotation, namespaceAnnotation, false)

    val s = SchemaBuilder.enumeration(namer.name).namespace(namer.namespace).symbols(syms: _*)
    props.foreach { case (key, value) =>
      s.addProp(key, value)
    }

    override def schema(namingStrategy: NamingStrategy) = s
  }

  // A coproduct is a union, or a generalised either.
  // A :+: B :+: C :+: CNil is a type that is either an A, or a B, or a C.

  // Shapeless's implementation builds up the type recursively,
  // (i.e., it's actually A :+: (B :+: (C :+: CNil)))
  // so here we define the schema for the base case of the recursion, C :+: CNil
  implicit def coproductBaseSchema[S](implicit basefor: SchemaFor[S]): SchemaFor[S :+: CNil] = new SchemaFor[S :+: CNil] {

    import scala.collection.JavaConverters._

    override def schema(namingStrategy: NamingStrategy) = {
      val base = basefor.schema(namingStrategy)
      val schemas = scala.util.Try(base.getTypes.asScala).getOrElse(Seq(base))
      Schema.createUnion(schemas.asJava)
    }
  }

  // And here we continue the recursion up.
  implicit def coproductSchema[S, T <: Coproduct](implicit basefor: SchemaFor[S], coproductFor: SchemaFor[T]): SchemaFor[S :+: T] = new SchemaFor[S :+: T] {
    override def schema(namingStrategy: NamingStrategy) = {
      val base = basefor.schema(namingStrategy)
      val coproduct = coproductFor.schema(namingStrategy)
      SchemaHelper.createSafeUnion(base, coproduct)
    }
  }

  implicit def tuple2SchemaFor[A, B](implicit a: SchemaFor[A], b: SchemaFor[B]): SchemaFor[(A, B)] = new SchemaFor[(A, B)] {
    override def schema(namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple2").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema(namingStrategy)).noDefault()
        .name("_2").`type`(b.schema(namingStrategy)).noDefault()
        .endRecord()
  }

  implicit def tuple3SchemaFor[A, B, C](implicit
                                        a: SchemaFor[A],
                                        b: SchemaFor[B],
                                        c: SchemaFor[C]): SchemaFor[(A, B, C)] = new SchemaFor[(A, B, C)] {
    override def schema(namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple3").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema(namingStrategy)).noDefault()
        .name("_2").`type`(b.schema(namingStrategy)).noDefault()
        .name("_3").`type`(c.schema(namingStrategy)).noDefault()
        .endRecord()
  }

  implicit def tuple4SchemaFor[A, B, C, D](implicit
                                           a: SchemaFor[A],
                                           b: SchemaFor[B],
                                           c: SchemaFor[C],
                                           d: SchemaFor[D]): SchemaFor[(A, B, C, D)] = new SchemaFor[(A, B, C, D)] {
    override def schema(namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple4").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema(namingStrategy)).noDefault()
        .name("_2").`type`(b.schema(namingStrategy)).noDefault()
        .name("_3").`type`(c.schema(namingStrategy)).noDefault()
        .name("_4").`type`(d.schema(namingStrategy)).noDefault()
        .endRecord()
  }

  implicit def tuple5SchemaFor[A, B, C, D, E](implicit
                                              a: SchemaFor[A],
                                              b: SchemaFor[B],
                                              c: SchemaFor[C],
                                              d: SchemaFor[D],
                                              e: SchemaFor[E]): SchemaFor[(A, B, C, D, E)] = new SchemaFor[(A, B, C, D, E)] {
    override def schema(namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple5").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema(namingStrategy)).noDefault()
        .name("_2").`type`(b.schema(namingStrategy)).noDefault()
        .name("_3").`type`(c.schema(namingStrategy)).noDefault()
        .name("_4").`type`(d.schema(namingStrategy)).noDefault()
        .name("_5").`type`(e.schema(namingStrategy)).noDefault()
        .endRecord()
  }

  implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]
}
