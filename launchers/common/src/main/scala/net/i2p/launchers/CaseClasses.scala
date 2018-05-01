package net.i2p.launchers

import java.io.{File, InputStream}

/**
  * This represent a file or a directory. A filesystem resource.
  * It's used to handle a correct deployment of base files, even
  * installer resources got kind of a flat list. (i.e geoip files
  * is placed under a dir geoip)
  *
  * With this class we can now have a list containing both directories
  * and files in a type safe list.
  *
  * @param path
  * @param content
  * @param files
  * @since 0.9.35
  */
class FDObject(path: String, content: Option[InputStream], files: Option[List[File]] = None, subDirectories: Boolean = false) {
  def isFile = files.isEmpty

  def getPath = path

  def filesIsDirectories = subDirectories

  def getContent: Either[InputStream, List[File]] = {
    if (files.isEmpty) return Left(content.get)
    Right(files.get.map { f => new File(DeployProfile.pathJoin(path, f.getName)) })
  }
}


class FDObjFile(file: String) extends FDObject(new File(file).getPath, Some(getClass.getResourceAsStream("/".concat(file))) )
class FDObjDir(name: String, files: List[String],subDirectories:Boolean=false) extends FDObject(name, None, Some(files.map { s => new File(s) }),subDirectories)

/**
  *
  * This is a case class, it's a Scala thing. Think of it as a type.
  * If you're familiar with C, it's like a "typedef" even iirc Scala
  * has a own function for that as well.
  *
  *
  */


