package com.johnsnowlabs.nlp.annotators.sda.pragmatic

import com.johnsnowlabs.nlp.annotators.common.TokenizedWithSentence
import com.johnsnowlabs.nlp.serialization.MapFeature
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, ParamsAndFeaturesReadable}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.param.{BooleanParam, DoubleParam}
import org.apache.spark.ml.util.Identifiable

/**
  * Created by saif on 12/06/2017.
  */

/**
  * Gives a good or bad score to a sentence based on the approach used
  * @param uid internal uid needed for saving annotator to disk
  * @@ model: Implementation to be applied for sentiment analysis
  */
class SentimentDetectorModel(override val uid: String) extends AnnotatorModel[SentimentDetectorModel] {

  import com.johnsnowlabs.nlp.AnnotatorType._

  val sentimentDict = new MapFeature[String, String](this, "sentimentDict")

  lazy val model: PragmaticScorer =
    new PragmaticScorer($$(sentimentDict), $(positiveMultiplier), $(negativeMultiplier), $(incrementMultiplier), $(decrementMultiplier), $(reverseMultiplier))

  override val outputAnnotatorType: AnnotatorType = SENTIMENT

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(TOKEN, DOCUMENT)

  def this() = this(Identifiable.randomUID("SENTIMENT"))

  val positiveMultiplier = new DoubleParam(this, "positiveMultiplier", "multiplier for positive sentiments. Defaults 1.0")
  val negativeMultiplier = new DoubleParam(this, "negativeMultiplier", "multiplier for negative sentiments. Defaults -1.0")
  val incrementMultiplier = new DoubleParam(this, "incrementMultiplier", "multiplier for increment sentiments. Defaults 2.0")
  val decrementMultiplier = new DoubleParam(this, "decrementMultiplier", "multiplier for decrement sentiments. Defaults -2.0")
  val reverseMultiplier = new DoubleParam(this, "reverseMultiplier", "multiplier for revert sentiments. Defaults -1.0")
  val enableScore = new BooleanParam(this, "enableScore", "if true, score will show as a string type containing a double value, else will output string \"positive\" or \"negative\". Defaults false")

  def setPositiveMultipler(v: Double): this.type = set(positiveMultiplier, v)
  def setNegativeMultipler(v: Double): this.type = set(negativeMultiplier, v)
  def setIncrementMultipler(v: Double): this.type = set(incrementMultiplier, v)
  def setDecrementMultipler(v: Double): this.type = set(decrementMultiplier, v)
  def setReverseMultipler(v: Double): this.type = set(reverseMultiplier, v)
  def setEnableScore(v: Boolean): this.type = set(enableScore, v)

  def setSentimentDict(value: Map[String, String]): this.type = set(sentimentDict, value)

  /**
    * Tokens are needed to identify each word in a sentence boundary
    * POS tags are optionally submitted to the model in case they are needed
    * Lemmas are another optional annotator for some models
    * Bounds of sentiment are hardcoded to 0 as they render useless
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation], recursivePipeline: Option[PipelineModel]): Seq[Annotation] = {
    val tokenizedSentences = TokenizedWithSentence.unpack(annotations)

    val score = model.score(tokenizedSentences.toArray)

    Seq(Annotation(
      outputAnnotatorType,
      0,
      0,
      { if ($(enableScore)) score.toString else if (score >= 0) "positive" else "negative"},
      Map.empty[String, String]
    ))
  }

}
object SentimentDetectorModel extends ParamsAndFeaturesReadable[SentimentDetectorModel]