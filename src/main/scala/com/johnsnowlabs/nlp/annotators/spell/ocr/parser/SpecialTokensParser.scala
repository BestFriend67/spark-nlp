package com.johnsnowlabs.nlp.annotators.spell.ocr.parser

import com.github.liblevenshtein.transducer.factory.TransducerBuilder
import com.github.liblevenshtein.transducer.{Algorithm, Candidate, ITransducer}
import com.johnsnowlabs.nlp.annotators.spell.ocr.WeightedLevenshtein
import com.navigamez.greex.GreexGenerator

import scala.collection.JavaConversions._


trait PreprocessingParser {
  def separate(token:String): String
}


trait SpecialClassParser {

  val label:String

  val transducer : ITransducer[Candidate]

  val maxDist: Int

  def generateTransducer: ITransducer[Candidate]

  def replaceWithLabel(tmp: String): String = {
    if(transducer.transduce(tmp, 0).toList.isEmpty)
      tmp
    else
      label
  }
}

trait RegexParser extends SpecialClassParser {

  val regex:String

  override def generateTransducer: ITransducer[Candidate] = {
    import scala.collection.JavaConversions._

    // first step, enumerate the regular language
    val generator = new GreexGenerator(regex)
    val matches = generator.generateAll

    // second step, create the transducer
    new TransducerBuilder().
      dictionary(matches.toList.sorted, true).
      algorithm(Algorithm.STANDARD).
      defaultMaxDistance(maxDist).
      includeDistance(true).
      build[Candidate]
  }

}

trait VocabParser extends SpecialClassParser {

  val vocab: Set[String]

  def generateTransducer: ITransducer[Candidate] = {
    import scala.collection.JavaConversions._

    // second step, create the transducer
    new TransducerBuilder().
      dictionary(vocab.toList.sorted, true).
      algorithm(Algorithm.STANDARD).
      defaultMaxDistance(maxDist).
      includeDistance(true).
      build[Candidate]
  }

  def loadCSV(path:String, col:Option[String] = None) = {
    scala.io.Source.fromFile(path).getLines.toSet
  }
}


case class CandidateSplit(candidates:Seq[Seq[String]], cost:Float=0f) {
  def appendLeft(token: String) = {
    CandidateSplit(candidates :+ Seq(token))
  }
}


class SuffixedToken(suffixes:Array[String]) extends PreprocessingParser {

  def belongs(token: String): Boolean =
    if(token.length > 1)
       suffixes.map(token.endsWith).reduce(_ || _)
    else
       false

  override def separate(token:String): String = {
    if(belongs(token)) {
      s"""${separate(token.dropRight(1))} ${token.last}"""
    }
    else
      token
  }

}

object SuffixedToken {
  def apply(suffixes:Array[String]) = new SuffixedToken(suffixes)
}


class PrefixedToken(prefixes:Array[String]) extends PreprocessingParser {

  private def parse(token:String)  =
    (token.head.toString, token.tail)

  def belongs(token: String): Boolean =
    if(token.length > 1)
      prefixes.map(token.head.toString.equals).reduce(_ || _)
    else
      false

  override def separate(token:String): String = {
    if (belongs(token))
        s"""${token.head} ${separate(token.tail)}"""
    else
        token
  }
}

object PrefixedToken {
  def apply(prefixes:Array[String]) = new PrefixedToken(prefixes)
}


object DateToken extends RegexParser with WeightedLevenshtein{

  override val regex = "(01|02|03|04|05|06|07|08|09|10|11|12)\\/([0-2][0-9]|30|31)\\/(19|20)[0-9]{2}|[0-9]{2}\\/(19|20)[0-9]{2}|[0-2][0-9]:[0-5][0-9]"
  override val transducer: ITransducer[Candidate] = generateTransducer
  override val label = "_DATE_"
  override val maxDist: Int = 2

  val dateRegex = "(01|02|03|04|05|06|07|08|09|10|11|12)/[0-3][0-9]/(1|2)[0-9]{3}".r

  def separate(word: String): String = {
    val matcher = dateRegex.pattern.matcher(word)
    if (matcher.matches) {
      word.replace(matcher.group(0), label)
    }
    else
      word
  }

  override def replaceWithLabel(tmp: String): String = separate(tmp)

}

object NumberToken extends RegexParser {

  /* used during candidate generation(correction) - must be finite */
  override val regex = "([0-9]{1,3}(\\.|,)[0-9]{1,3}|[0-9]{1,2}(\\.[0-9]{1,2})?(%)?|[0-9]{1,4})"

  override val transducer: ITransducer[Candidate] = generateTransducer

  override val label = "_NUM_"

  override val maxDist: Int = 2

  /* used to parse corpus - potentially infite */
  private val numRegex =
    """(\-|#|\$)?([0-9]+\.[0-9]+\-[0-9]+\.[0-9]+|[0-9]+/[0-9]+|[0-9]+\-[0-9]+|[0-9]+\.[0-9]+|[0-9]+,[0-9]+|[0-9]+\-[0-9]+\-[0-9]+|[0-9]+)""".r

  def separate(word: String): String = {
    val matcher = numRegex.pattern.matcher(word)
    if(matcher.matches) {
      val result = word.replace(matcher.group(0), label)
      result
    }
    else
      word
  }

  override def replaceWithLabel(tmp: String): String = separate(tmp)

}
