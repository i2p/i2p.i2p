package net.i2p.launchers

import java.io.{File, FileInputStream, FileOutputStream, InputStream}
import java.nio.file.Path
import java.util.zip.ZipInputStream

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  *
  * CompleteDeployment - In use to deploy base path for the Mac OS X Bundle release.
  *
  * @author Meeh
  * @since 0.9.35
  */
class CompleteDeployment(val zipFile: File, val i2pBaseDir: File) {

  if (!i2pBaseDir.exists()) {
    i2pBaseDir.mkdirs()
  } else {
    // TODO: Check what version etc..
  }

  def unzip(zipFile: InputStream, destination: Path): Unit = {
    val zis = new ZipInputStream(zipFile)

    Stream.continually(zis.getNextEntry).takeWhile(_ != null).foreach { file =>
      if (!file.isDirectory) {
        val outPath = destination.resolve(file.getName)
        val outPathParent = outPath.getParent
        if (!outPathParent.toFile.exists()) {
          outPathParent.toFile.mkdirs()
        }

        val outFile = outPath.toFile
        val out = new FileOutputStream(outFile)
        val buffer = new Array[Byte](4096)
        Stream.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(out.write(buffer, 0, _))
      }
    }
  }

  def makeDeployment : Future[Unit] = Future {
    unzip(new FileInputStream(zipFile), i2pBaseDir.toPath)
  }

}
