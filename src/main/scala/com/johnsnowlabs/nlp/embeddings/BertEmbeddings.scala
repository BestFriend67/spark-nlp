package com.johnsnowlabs.nlp.embeddings

import java.io.File

import com.johnsnowlabs.ml.tensorflow._
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.nlp.annotators.ner.dl.LoadsContrib
import com.johnsnowlabs.nlp.annotators.tokenizer.wordpiece.{BasicTokenizer, WordpieceEncoder}
import com.johnsnowlabs.nlp.serialization.MapFeature
import com.johnsnowlabs.nlp.util.io.{ExternalResource, ReadAs, ResourceHelper}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.param.{IntArrayParam, IntParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, SparkSession}


class BertEmbeddings(override val uid: String) extends
  AnnotatorModel[BertEmbeddings]
  with WriteTensorflowModel
  with HasEmbeddings
{

  def this() = this(Identifiable.randomUID("BERT_EMBEDDINGS"))

  val batchSize = new IntParam(this, "batchSize", "Batch size. Large values allows faster processing but requires more memory.")
  val vocabulary: MapFeature[String, Int] = new MapFeature(this, "vocabulary")
  val configProtoBytes = new IntArrayParam(this, "configProtoBytes", "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()")
  val maxSentenceLength = new IntParam(this, "maxSentenceLength", "Max sentence length to process")
  val poolingLayer = new IntParam(this, "poolingLayer", "Set BERT pooling layer to: -1 for last hiddent layer, -2 for second-to-last hiddent layer, and 0 for first layer which is called embeddings")

  def sentenceStartTokenId: Int = {
    $$(vocabulary)("[CLS]")
  }

  def sentenceEndTokenId: Int = {
    $$(vocabulary)("[SEP]")
  }

  override def setDimension(value: Int): this.type = {
    if(get(dimension).isEmpty)
      set(this.dimension, value)
    this

  }

  override def setCaseSensitive(value: Boolean): this.type = {
    if(get(caseSensitive).isEmpty)
      set(this.caseSensitive, value)
    this
  }

  def setBatchSize(size: Int): this.type = {
    if(get(batchSize).isEmpty)
      set(batchSize, size)
    this
  }

  def setVocabulary(value: Map[String, Int]): this.type = set(vocabulary, value)

  def setConfigProtoBytes(bytes: Array[Int]): BertEmbeddings.this.type = set(this.configProtoBytes, bytes)

  def setMaxSentenceLength(value: Int): this.type = {
    if(get(maxSentenceLength).isEmpty)
      set(maxSentenceLength, value)
    this
  }

  def setPoolingLayer(layer: Int): this.type = {
    layer match {
      case 0 => set(poolingLayer, 0)
      case -1 => set(poolingLayer, -1)
      case -2 => set(poolingLayer, -2)
      case _ => throw new MatchError("poolingLayer must be either 0, -1, or -2: first layer (embeddings), last layer, second-to-last layer")
    }
  }

  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  def getMaxSentenceLength: Int = $(maxSentenceLength)

  def getPoolingLayer: Int = $(poolingLayer)

  setDefault(
    dimension -> 768,
    batchSize -> 32,
    maxSentenceLength -> 64,
    poolingLayer -> 0
  )

  private var _model: Option[Broadcast[TensorflowBert]] = None
  def getModelIfNotSet: TensorflowBert = _model.get.value
  def setModelIfNotSet(spark: SparkSession, tensorflow: TensorflowWrapper): this.type = {
    if (_model.isEmpty) {

      _model = Some(
        spark.sparkContext.broadcast(
          new TensorflowBert(
            tensorflow,
            sentenceStartTokenId,
            sentenceEndTokenId,
            maxSentenceLength = $(maxSentenceLength),
            batchSize = $(batchSize),
            dimension = $(dimension),
            caseSensitive = $(caseSensitive),
            configProtoBytes = getConfigProtoBytes
          )
        )
      )
    }

    this
  }
  def tokenize(sentences: Seq[Sentence]): Seq[WordpieceTokenizedSentence] = {
    val basicTokenizer = new BasicTokenizer($(caseSensitive))
    val encoder = new WordpieceEncoder($$(vocabulary))

    sentences.map { s =>
      val tokens = basicTokenizer.tokenize(s)
      val wordpieceTokens = tokens.flatMap(token => encoder.encode(token))
      WordpieceTokenizedSentence(wordpieceTokens)
    }
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = SentenceSplit.unpack(annotations)
    val tokenizedSentences = TokenizedWithSentence.unpack(annotations)

    val tokenized = tokenize(sentences)
    val withEmbeddings = getModelIfNotSet.calculateEmbeddings(tokenized, tokenizedSentences, $(poolingLayer))
    WordpieceEmbeddingsSentence.pack(withEmbeddings)
  }

  override def afterAnnotate(dataset: DataFrame): DataFrame = {
    dataset.withColumn(getOutputCol, wrapEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension)))
  }

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val inputAnnotatorTypes = Array(AnnotatorType.DOCUMENT, AnnotatorType.TOKEN)
  override val outputAnnotatorType: AnnotatorType = AnnotatorType.WORD_EMBEDDINGS

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModel(path, spark, getModelIfNotSet.tensorflow, "_bert", BertEmbeddings.tfFile, configProtoBytes = getConfigProtoBytes)
  }
}

trait ReadablePretrainedBertModel extends ParamsAndFeaturesReadable[BertEmbeddings] with HasPretrained[BertEmbeddings] {
  override protected val defaultModelName: String = "bert_uncased"
}

trait ReadBertTensorflowModel extends ReadTensorflowModel {
  this:ParamsAndFeaturesReadable[BertEmbeddings] =>

  override val tfFile: String = "bert_tensorflow"

  def readTensorflow(instance: BertEmbeddings, path: String, spark: SparkSession): Unit = {
    val tf = readTensorflowModel(path, spark, "_bert_tf")
    instance.setModelIfNotSet(spark, tf)
  }

  addReader(readTensorflow)

  def loadFromPython(folder: String, spark: SparkSession): BertEmbeddings = {
    val f = new File(folder)
    val vocab = new File(folder, "vocab.txt")
    require(f.exists, s"Folder $folder not found")
    require(f.isDirectory, s"File $folder is not folder")
    require(vocab.exists(), s"Vocabulary file vocab.txt not found in folder $folder")

    LoadsContrib.loadContribToCluster(spark)

    val wrapper = TensorflowWrapper.read(folder, zipped = false)

    val vocabResource = new ExternalResource(vocab.getAbsolutePath, ReadAs.LINE_BY_LINE, Map("format" -> "text"))
    val words = ResourceHelper.parseLines(vocabResource).zipWithIndex.toMap

    new BertEmbeddings()
      .setVocabulary(words)
      .setModelIfNotSet(spark, wrapper)
  }
}


object BertEmbeddings extends ReadablePretrainedBertModel with ReadBertTensorflowModel
