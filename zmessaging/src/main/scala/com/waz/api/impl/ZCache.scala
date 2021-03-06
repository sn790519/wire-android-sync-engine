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
package com.waz.api.impl

import java.io.OutputStream

import com.waz.cache.{CacheEntry, CacheService, Expiration}

import scala.concurrent.duration._

class ZCache(cache: CacheService) extends com.waz.api.ZCache {
  private implicit val expiration: Expiration = 1.hour

  override def createTempEntry(): ZCache.Entry = new ZCache.Entry(cache.createManagedFile())
}

object ZCache {
  case class Entry(item: CacheEntry) extends com.waz.api.ZCache.Entry {
    override def openOutputStream(): OutputStream = item.outputStream
  }
}
