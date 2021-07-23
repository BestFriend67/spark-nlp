package com.johnsnowlabs.nlp.util.io

import com.johnsnowlabs.util.{ConfigHelper, ConfigLoader}
import org.apache.hadoop.fs.{FileSystem, Path}

import java.io.{File, FileWriter, PrintWriter}
import scala.language.existentials


object OutputHelper {

  private lazy val fileSystem = ConfigHelper.getFileSystem

  private def logsFolder: String = ConfigLoader.getConfigStringValue(ConfigHelper.annotatorLogFolder)

  lazy private val isDBFS = fileSystem.getScheme.equals("dbfs")

  def writeAppend(uuid: String, content: String, outputLogsPath: String): Unit = {
    val targetFolder = if (outputLogsPath.isEmpty) logsFolder else outputLogsPath

    if (isDBFS) {
      if (!new File(targetFolder).exists()) new File(targetFolder).mkdirs()
    }else{
      if (!fileSystem.exists(new Path(targetFolder))) fileSystem.mkdirs(new Path(targetFolder))
    }

    val targetPath = new Path(targetFolder, uuid + ".log")

    if (fileSystem.getScheme.equals("file") || fileSystem.getScheme.equals("dbfs")) {
      val fo = new File(targetPath.toUri.getRawPath)
      val writer = new FileWriter(fo, true)
      writer.append(content + System.lineSeparator())
      writer.close()
    } else {
      fileSystem.createNewFile(targetPath)
      val fo = fileSystem.append(targetPath)
      val writer = new PrintWriter(fo, true)
      writer.append(content + System.lineSeparator())
      writer.close()
      fo.close()

    }
  }

}
