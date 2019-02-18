package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.AnnotatorType.{DOCUMENT, TOKEN, WORD_EMBEDDINGS}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, ParamsAndFeaturesWritable}
import com.johnsnowlabs.nlp.annotators.common.{TokenPieceEmbeddings, TokenizedWithSentence, WordpieceEmbeddingsSentence}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}


class WordEmbeddingsLookupModel(override val uid: String)
  extends AnnotatorModel[WordEmbeddingsLookupModel]
    with ModelWithWordEmbeddings
    with ParamsAndFeaturesWritable {

  def this() = this(Identifiable.randomUID("EMBEDDINGS_LOOKUP_MODEL"))

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = TokenizedWithSentence.unpack(annotations)
    val withEmbeddings = sentences.map{s =>
      val tokens = s.indexedTokens.map {token =>
        val vector = this.getEmbeddings.getEmbeddingsVector(token.token)
        new TokenPieceEmbeddings(token.token, token.token, -1, true, vector, token.begin, token.end)
      }
      WordpieceEmbeddingsSentence(tokens)
    }

    WordpieceEmbeddingsSentence.pack(withEmbeddings)
  }

  override val annotatorType: AnnotatorType = WORD_EMBEDDINGS
  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val requiredAnnotatorTypes: Array[String] = Array(DOCUMENT, TOKEN)
}

object WordEmbeddingsLookupModel extends DefaultParamsReadable[WordEmbeddingsLookupModel]
