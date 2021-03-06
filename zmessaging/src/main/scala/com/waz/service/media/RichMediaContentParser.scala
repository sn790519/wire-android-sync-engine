/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.media

import java.net.URLDecoder

import android.net.Uri
import android.util.Patterns
import com.waz.ZLog._
import com.waz.api.Message.Part
import com.waz.api.Message.Part.Type._
import com.waz.model.MessageContent

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class RichMediaContentParser {
  import Part.Type._
  import com.waz.service.media.RichMediaContentParser._

  private implicit val logTag: LogTag = logTagFor[RichMediaContentParser]

  def findMatches(content: String, weblinkEnabled: Boolean = false) = {

    val knownDomains = Map(
      "youtu.be" -> YOUTUBE,
      "youtube.com" -> YOUTUBE,
//      "twitter.com" -> TWITTER,
      "soundcloud.com" -> SOUNDCLOUD,
      "open.spotify.com" -> SPOTIFY
    )

    def validate(uri: Uri, tpe: Part.Type): Boolean = tpe match {
      case YOUTUBE     => youtubeVideoId(uri).isDefined
      case SOUNDCLOUD  => Option(uri.getPath).exists(_.nonEmpty)
      case TWITTER     => uri.toString.matches(TwitterRegex.regex)
      case SPOTIFY     => SpotifyPathRegex.unapplySeq(uri.getPath).isDefined
      case WEB_LINK    => weblinkEnabled
      case _           => false
    }

    def matchDomain(host: String): Part.Type = knownDomains.getOrElse(host, {
      val dot = host.indexOf('.')
      if (dot >= 0) matchDomain(host.substring(dot + 1))
      else WEB_LINK
    })

    val m = Patterns.WEB_URL.matcher(content)
    Iterator.continually(m.find()).takeWhile(identity).map { _ =>
      val start = m.start
      val end = m.end
      if (start == 0 || content(start - 1) != '@') {
        val uri = Uri.parse(URLDecoder.decode(m.group(), "UTF-8"))
        Option(uri.getHost) map(_.toLowerCase) map matchDomain flatMap { tpe =>
          if (validate(uri, tpe)) Some((start, end, tpe))
          else None
        }
      } else None
    }.flatten
  }

  def splitContent(content: String, weblinkEnabled: Boolean = false) = {
    try {
      val res = new MessageContentBuilder

      val end = findMatches(content, weblinkEnabled).foldLeft(0) { case (start, (matchStart, matchEnd, tpe)) =>
        if (start < matchStart) res += content.substring(start, matchStart)
        res += (tpe, content.substring(matchStart, matchEnd))
        matchEnd
      }

      if (end < content.length) res += content.substring(end)

      res.result()
    } catch {
      case e: Throwable =>
        error("got error while parsing message content", e)
        Seq(MessageContent(TEXT, content))
    }
  }

  def javaSplitContent(content: String) = splitContent(content).asJava
}

object RichMediaContentParser {
  case class GoogleMapsLocation(x: String, y: String, zoom: String)

  val SpotifyPathRegex = "(?i)/(artist|album|track|playlist)/[0-9A-Za-z-_]+/?".r
  val TwitterRegex = """(?i)(https?://)?(www\.)?twitter\.com/[0-9A-Za-z-_]+/status/\d*/?""".r

  def youtubeVideoId(youtubeUrl: String): Option[String] = decode(youtubeUrl)(youtubeVideoId)
  private def youtubeVideoId(uri: Uri): Option[String] = try {
    Option(uri.getQueryParameter("v")).orElse {
      Option(uri.getLastPathSegment)
    }.filter(_.length > 10) // currently id is always 11 chars, this may change in future
  } catch {
    case NonFatal(e) => None
  }

  private def decode[T](url: String)(op: Uri => Option[T]): Option[T] = op(Uri.parse(URLDecoder.decode(url, "UTF-8")))

  def textMessageContent(part: String) = MessageContent(if (containsOnlyEmojis(part)) TEXT_EMOJI_ONLY else TEXT, part)

  def containsOnlyEmojis(part: String): Boolean = {

    val iter = part.iterator

    def emoji(hs: Char) = hs match {
      case 0xa9 | 0xae | 0x303d | 0x3030 | 0x2b55 | 0x2b1c | 0x2b1b | 0x2b50 | 0x203c | 0x2049 => true
      case _ if 0x2100 <= hs && hs <= 0x27ff => true
      case _ if 0x2B05 <= hs && hs <= 0x2b07 => true
      case _ if 0x2934 <= hs && hs <= 0x2935 => true
      case _ if 0x3297 <= hs && hs <= 0x3299 => true
      case _ if 0xd800 <= hs && hs <= 0xdbff => // surrogate pair
        iter.hasNext && {
          val ls = iter.next()
          val uc = ((hs - 0xd800) * 0x400) + (ls - 0xdc00) + 0x10000
          0x1d000 <= uc && uc <= 0x1f9c0
        }
      case _ =>
        iter.hasNext && {
          iter.next() match {
            case 0x20e3 | 0xfe0f | 0xd83c => true
            case _ => false
          }
        }
    }

    while (iter.hasNext) {
      val hs = iter.next()
      if (!Character.isWhitespace(hs) && !emoji(hs)) return false
    }

    true
  }
}

class MessageContentBuilder {
  val res = Seq.newBuilder[MessageContent]

  def +=(part: String) = {
    val trimmed = part.trim
    if (trimmed.nonEmpty) res += RichMediaContentParser.textMessageContent(trimmed)
  }

  def +=(tpe: Part.Type, part: String) = {
    val trimmed = part.trim
    if (trimmed.nonEmpty) res += MessageContent(tpe, trimmed)
  }

  def +=(content: MessageContent) = res += content

  def ++=(ct: Seq[MessageContent]) = res ++= ct

  def result() = res.result()
}