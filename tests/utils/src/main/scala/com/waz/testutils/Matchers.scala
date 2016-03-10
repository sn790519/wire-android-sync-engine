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
package com.waz.testutils

import java.util.concurrent.TimeoutException

import android.graphics.Bitmap
import com.waz.RobolectricUtils
import com.waz.api.ImageAsset.BitmapCallback
import com.waz.api.{ImageAsset, Message, User}
import com.waz.model.UserId
import com.waz.utils._
import com.waz.utils.events._
import org.robolectric.Robolectric
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.{Nanoseconds, Span}
import org.threeten.bp.Instant

import scala.Either.cond
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Awaitable, Future, Promise}
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object Matchers {
  import DefaultPatience.{PatienceConfig, scaled, spanScaleFactor}
  import org.scalatest.Matchers._
  import org.scalatest.OptionValues._

  val beLocal = new LocalEventMatcher
  def eventually[T](m: Matcher[T])(implicit timeout: FiniteDuration = 5.seconds): Matcher[Awaitable[T]] = m compose (x => Await.result(x, timeout * spanScaleFactor))

  def beMatching[A](pf: PartialFunction[Any, Any]): Matcher[A] = new Matcher[A] {
    override def apply(left: A): MatchResult = MatchResult(pf.isDefinedAt(left), left + " doesn't match given pattern", left + " matches given pattern")
  }

  def succeed[A](implicit timeout: FiniteDuration = 5.seconds): Matcher[Future[A]] = new Matcher[Future[A]] {
    override def apply(awaitable: Future[A]): MatchResult = {
      val expectable = Await.ready(awaitable, timeout * spanScaleFactor).value.get
      MatchResult(expectable.isSuccess, s"$expectable did not succeed", s"$expectable succeeded")
    }
  }

  def failWith[A: ClassTag](implicit timeout: FiniteDuration = 5.seconds): Matcher[Future[_]] = new Matcher[Future[_]] {
    override def apply(awaitable: Future[_]): MatchResult = {
      val expectable = Await.ready(awaitable, timeout * spanScaleFactor).value.get
      MatchResult(expectable match { case Failure(_: A) => true; case _ => false }, s"$expectable did not fail with ${implicitly[ClassTag[A]].runtimeClass.getSimpleName}", s"$expectable failed (as expected) with ${implicitly[ClassTag[A]].runtimeClass.getSimpleName}")
    }
  }

  case class expectThat[A](implicit clazz: ClassTag[A]) {
    def isThrownBy(f: => Unit): Unit = {
      var expectedWasThrown = false
      try f catch {
        case exc: TestFailedException => throw exc
        case expected: A => expectedWasThrown = true
        case other: Throwable => fail(s"expected ${clazz.runtimeClass.getName} but ${other.getClass.getName} was thrown instead", other)
      }
      if (! expectedWasThrown) fail(s"expected ${clazz.runtimeClass.getName} but no exception was thrown")
    }
  }

  def withEvent[A, B](p: EventStream[A])(f: PartialFunction[A, Boolean])(body: => B)(implicit timeout: FiniteDuration = 15.seconds): B = {
    import com.waz.utils.events.EventContext.Implicits.global

    var gotNotification = false
    p {
      f.andThen(if (_) gotNotification = true)
    }

    val result = body // force evaluation

    withClue("Notification was not received") {
      RobolectricUtils.withDelay(if (!gotNotification) throw new Exception("notification missing"))(timeout)
    }

    result
  }

  import Implicits._

  def beLiked = be('liked)
  def beLikedByThisUser = be('likedByThisUser)
  def haveLikesFrom(ids: UserId*): Matcher[Message] = contain.theSameElementsInOrderAs(ids).matcher[Seq[UserId]] compose ((_: Message).getLikes.map(_.data.id))
  def notHaveLikes: Matcher[Message] = be(empty).matcher[Seq[User]] compose ((_: Message).getLikes)

  implicit class ImageAssetSyntax(val im: ImageAsset) extends AnyVal {
    def shouldBeAnAnimatedGif: Bitmap = {
      (im.data.versions should not be empty).soon
      val promisedMoreThanTwoUpdates = Promise[Bitmap]

      @volatile var count = 0
      val handle = im.getBitmap(500, new BitmapCallback {
        override def onBitmapLoadingFailed(): Unit = promisedMoreThanTwoUpdates.tryFailure(new AssertionError("GIF loading failed"))
        override def onBitmapLoaded(b: Bitmap, isPreview: Boolean): Unit = {
          count += 1
          if (count > 2) promisedMoreThanTwoUpdates.trySuccess(b)
        }
      })

      returning(promisedMoreThanTwoUpdates.future.await(s"GIF should get more than 2 updates from loader but got only $count update(s)")(patience(20.seconds)))(_ => handle.cancel())
    }
  }

  def patience(timeout: FiniteDuration = 5.seconds, interval: FiniteDuration = 20.millis) = DefaultPatience.PatienceConfig(scaled(Span(timeout.toNanos, Nanoseconds)), scaled(Span(interval.toNanos, Nanoseconds)))

  implicit class FiniteDurationSyntax(val t: FiniteDuration) extends AnyVal {
    def timeout: PatienceConfig = patience(timeout = t)
    def tolerance: Tolerance = Tolerance(t)
  }

  def within[A](timeout: FiniteDuration)(f: => A)(implicit p: PatienceConfig): A =
    soon(f)(p.copy(timeout = scaled(Span(timeout.toNanos, Nanoseconds))))

  implicit class FutureSyntax[A, B](val f: A)(implicit ev: A => Future[B]) {
    def afterwards[C](clue: => String = "")(g: B => C)(implicit p: PatienceConfig): C =
      g(retryUntilRightOrTimeout(f.value.fold2(Left(()), Right(_)))(p) match {
        case Left(()) => throw new TimeoutException(s"future did not complete in time ($p)${if (clue.nonEmpty) ": " else ""}$clue")
        case Right(Failure(l)) => throw l
        case Right(Success(r)) => r
      })

    def await(clue: => String = "")(implicit p: PatienceConfig): B = afterwards(clue)(identity)
  }

  def soon[A](f: => A)(implicit p: PatienceConfig): A =
    retryUntilRightOrTimeout(try Right(f) catch { case NonFatal(e) => Left(e) })(p).fold(e => throw e, identity)

  def idle(someTime: FiniteDuration)(implicit p: PatienceConfig): Unit =
    retryUntilRightOrTimeout(Left(()))(p.copy(timeout = scaled(Span(someTime.toNanos, Nanoseconds))))

  case class Tolerance(t: FiniteDuration)

  def beRoughly(d: Instant)(implicit tolerance: Tolerance): Matcher[Option[Instant]] =
    (be >= (d.toEpochMilli - (tolerance.t * spanScaleFactor).toMillis) and be <= (d.toEpochMilli + (tolerance.t * spanScaleFactor).toMillis)) compose (_.value.toEpochMilli)

  implicit class RichMatcher[A](m: Matcher[A]) {
    def soon(implicit p: PatienceConfig): Matcher[A] = new Matcher[A] {
      def apply(a: A) = retryUntilRightOrTimeout(((r: MatchResult) => cond(r.matches, r, r))(m(a)))(p).merge
    }
  }

  implicit class WithinAndSoonPostfixes[A](f: => A) {
    def within(timeout: FiniteDuration)(implicit p: PatienceConfig): A = Matchers.this.soon(f)(p.copy(timeout = scaled(Span(timeout.toNanos, Nanoseconds))))
    def soon(implicit p: PatienceConfig): A = Matchers.this.soon(f)(p)
  }

  private def retryUntilRightOrTimeout[A, B](f: => Either[A, B])(p: PatienceConfig): Either[A, B] = {
    val startAt = System.nanoTime

    @tailrec def attempt(): Either[A, B] = {
      val result = f
      if (result.isRight) result
      else if (System.nanoTime - startAt > p.timeout.totalNanos) result else {
        Thread.sleep(p.interval.millisPart, p.interval.nanosPart)
        Robolectric.runBackgroundTasks()
        Robolectric.runUiThreadTasksIncludingDelayedTasks()
        attempt()
      }
    }

    attempt()
  }
}

trait DefaultPatienceConfig extends PatienceConfiguration {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(Matchers.patience().timeout, Matchers.patience().interval)
}

object DefaultPatience extends PatienceConfiguration {
  override implicit lazy val patienceConfig: PatienceConfig = Matchers.patience()
}