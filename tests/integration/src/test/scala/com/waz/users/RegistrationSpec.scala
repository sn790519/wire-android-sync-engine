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
package com.waz.users

import com.waz.api.ZMessagingApi.RegistrationListener
import com.waz.api._
import com.waz.api.impl.{AccentColor, EmailCredentials, LocalImageAsset}
import com.waz.client.RegistrationClient
import com.waz.model.ZUser.ZUserDao
import com.waz.model._
import com.waz.provision.EmailClientSuite
import com.waz.service.{BackendConfig, ContactsService}
import com.waz.testutils.prepareAddressBookEntries
import com.waz.testutils.Matchers._
import com.waz.threading.Threading
import com.waz.utils.IoUtils
import com.waz.znet.{AsyncClient, TestClientWrapper}
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import scala.concurrent.{Promise, Await}
import scala.concurrent.duration._
import scala.util.Random

class RegistrationSpec extends FeatureSpec with Matchers with GivenWhenThen with ApiSpec with EmailClientSuite { test =>

  implicit lazy val dispatcher = Threading.Background

  override val autoLogin: Boolean = false

  val hex = Random.nextInt().toHexString
  val hex1 = Random.nextInt().toHexString
  override val email = s"android.test+$hex@wearezeta.com"
  val email1 = s"android.test+$hex1@wearezeta.com"
  val email2 = s"android.test+${Random.nextInt().toHexString}@wearezeta.com"

  lazy val userId = ZUserId()

  lazy val client = new RegistrationClient(new AsyncClient(wrapper = TestClientWrapper), BackendConfig.EdgeBackend)

  lazy val assetGenerator = zmessaging.assetGenerator

  override def backendHostname: String = testBackend.baseUrl.stripPrefix("https://")

  def image = api.ui.images.getOrCreateImageAssetFrom(IoUtils.toByteArray(getClass.getResourceAsStream("/images/penguin.png"))).asInstanceOf[LocalImageAsset]

  lazy val assets = Await.result(assetGenerator.generateWireAsset(AssetId(), image.data.versions.last, RConvId(userId.str), profilePicture = true), 1.second)

  lazy val search = api.search()

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    prepareAddressBookEntries(
      Seq("1" -> "zbigniew@wearezeta.com", "3" -> "zbigniew+zbsz1@wearezeta.com", "4" -> "zbigniew+zbsz2@wearezeta.com", "5" -> "daniel@wearezeta.com"),
      Seq("1" -> "+123456789012", "2" -> "+4915324568954")
    )
  }

  feature("register new user") {

    scenario("delete verification emails") {
      info(s"deleted emails: ${emailClient.deleteVerificationEmails()}")
    }

    scenario("register a user with existing email") {
      val future = client.register(ZUserId(), EmailCredentials(EmailAddress("zbigniew@wearezeta.com"), Some(password)), s"test $hex", None) map {
        case Right((user, _)) => fail(s"created user: $user")
        case Left(error) => info(s"got error: $error")
      }

      Await.result(future, 30.seconds)
    }

    scenario("register with random email") {
      @volatile var regUser = None: Option[UserInfo]
      client.register(ZUserId(), EmailCredentials(EmailAddress(email1), Some(password)), s"test $hex1", accentId = Some(3)) map {
        case Right((user, _)) => regUser = Some(user)
        case Left(error) => fail(s"unexpected response: $error")
      }

      withDelay {
        regUser should be('defined)
      }

      regUser.get.email shouldEqual Some(EmailAddress(email1))
      regUser.get.name shouldEqual Some(s"test $hex1")
      regUser.get.accentId shouldEqual Some(3)

      info(s"registered: $regUser")

      awaitUi(5.second)
      withDelay {
        val link = emailClient.getVerificationLink(email1)
        link should be('defined)
        info(s"link: $link")
      } (1.minute)
    }

    scenario("login with zmessaging api using new email") {
      awaitUi(5.seconds)

      val promisedLogin = Promise[Self]
      api.login(CredentialsFactory.emailCredentials(email1, password), new LoginListener {
        override def onSuccess(user: Self): Unit = promisedLogin.success(user)
        override def onFailed(code: Int, message: String, label: String): Unit = promisedLogin.failure(new RuntimeException(s"$code, $message, $label"))
      })
      val selfUser = promisedLogin.future.await()(patience(1.minute))

      selfUser.isLoggedIn shouldEqual true
      selfUser.isEmailVerified shouldEqual false
      api.logout()
      awaitUi(1.second)
    }

    scenario("register using zmessaging api") {
      var selfUser: Self = null
      var failed = true

      api.register(CredentialsFactory.emailCredentials(email, password), s"test $hex", AccentColor(2), new RegistrationListener {
        override def onRegistrationFailed(code: Int, message: String, label: String): Unit = failed = true
        override def onRegistered(user: Self): Unit = {
          failed = false
          selfUser = user
        }
      })

      withDelay {
        failed shouldEqual false
        selfUser should not be null
        selfUser.isLoggedIn shouldEqual true
        selfUser.isEmailVerified shouldEqual false
        selfUser.getAccent shouldEqual AccentColor(2)
      }

      ZUserDao.list(globalModule.storage.dbHelper.getWritableDatabase).map(_.email).toSet shouldEqual Set(Some(EmailAddress(email)), Some(EmailAddress(email1)))
    }

    scenario("fresh login should return unverified user") {
      pauseApi()
      api.onDestroy()
      awaitUi(1.second)
      ZUserDao.deleteAll(globalModule.storage.dbHelper.getWritableDatabase)

      awaitUi(2.seconds)

      api = new impl.ZMessagingApi()
      api.onCreate(context)
      initApi()

      val promisedLogin = Promise[Self]
      api.login(CredentialsFactory.emailCredentials(email, password), new LoginListener {
        override def onSuccess(user: Self): Unit = promisedLogin.success(user)
        override def onFailed(code: Int, message: String, label: String): Unit = promisedLogin.failure(new RuntimeException(s"$code, $message, $label"))
      })
      val selfUser = promisedLogin.future.await()(patience(1.minute))

      selfUser.isLoggedIn shouldEqual true
      selfUser.isEmailVerified shouldEqual false
      selfUser.getEmail shouldEqual email
    }

    scenario("resend verification email") {
      withDelay ({
        emailClient.countVerificationEmails(email) shouldEqual 1
      }, delay = 5.seconds) (60.seconds)

      api.getSelf.isLoggedIn shouldEqual true
      api.getSelf.resendVerificationEmail(email)

      awaitUi(5.seconds)

      withDelay ({
        emailClient.countVerificationEmails(email) shouldEqual 2
      }, delay = 5.seconds) (60.seconds)
    }

    scenario("login automatically once email is verified") {
      api.getSelf.isLoggedIn shouldEqual true

      emailClient.verifyEmail(email)(email) shouldBe true

      withDelay {
        api.getSelf.isEmailVerified shouldEqual true
        api.getSelf.getUser should not be null
      } (1.minute)
    }

    scenario("retrieve contact search results") {
      api.zmessaging.foreach (_.prefs.editUiPreferences { _.putBoolean(ContactsService.PrefKey, true) })
      awaitUi(1.second)
      val users = search.getRecommendedPeople(20)
      withDelay { users.getAll should not be empty }(timeout = 20.seconds)
    }

    scenario("query for 'n'") {
      val users = search.getUsers("n", 20)
      withDelay { users.getAll should not be empty }(timeout = 20.seconds)
    }

    scenario("query for 'nnn'") {
      val users = search.getUsers("nnn", 20)
      withDelay { users.getAll should be(empty) }
    }

    scenario("query for top connected users") {
      val users = search.getTopPeople(20)
      awaitUi(1.second)
      withDelay { users.getAll should be(empty) }(timeout = 20.seconds)
    }
  }
}