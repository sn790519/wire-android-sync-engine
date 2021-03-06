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
package com.waz

import java.lang.System.currentTimeMillis
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.{Date, Locale}

import android.net.Uri
import android.util.Base64
import com.waz.api.{InvitationTokenFactory, Invitations}
import com.waz.bitmap.BitmapUtils.Mime
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.Text
import com.waz.model.UserData.ConnectionStatus
import com.waz.model.UserData.ConnectionStatus.{Accepted, PendingFromOther}
import com.waz.model._
import com.waz.model.messages.media._
import com.waz.model.otr.ClientId
import com.waz.model.sync.SyncRequest._
import com.waz.model.sync.{SyncJob, SyncRequest}
import com.waz.service.SearchKey
import com.waz.service.messages.MessageAndLikes
import com.waz.sync.client.OpenGraphClient.OpenGraphData
import com.waz.testutils.knownMimeTypes
import com.waz.utils.Locales.bcp47
import com.waz.utils.sha2
import com.waz.znet.AuthenticationManager.Token
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen._
import org.scalacheck._
import org.threeten.bp.{Duration, Instant}

import scala.concurrent.duration.FiniteDuration

object Generators {
  import MediaAssets._

  implicit lazy val genGcmEvent: Gen[Event] = {
    implicit val arbEventId: Arbitrary[EventId] = Arbitrary(genIncreasingEventId)
    implicit val arbConnectionStatus: Arbitrary[ConnectionStatus] = Arbitrary(oneOf(PendingFromOther, Accepted))

    oneOf[Event](
      resultOf(UserConnectionEvent.apply _),
      resultOf(ContactJoinEvent),
      resultOf(MessageAddEvent),
      resultOf(MemberJoinEvent),
      resultOf(MemberLeaveEvent),
      resultOf(RenameConversationEvent),
      resultOf(VoiceChannelDeactivateEvent(_: Uid, _: RConvId, _: EventId, _: Date, _: UserId, MissedCallEvent.MissedCallReason)))
  }

  implicit lazy val arbCallDeviceState: Arbitrary[CallDeviceState] = Arbitrary(resultOf(CallDeviceState))

  lazy val alphaNumStr = listOf(alphaNumChar).map(_.mkString)

  implicit lazy val arbUri: Arbitrary[Uri] = Arbitrary(for {
    scheme <- oneOf("file", "content", "http")
    path <- alphaNumStr
  } yield Uri.parse(s"$scheme://$path"))

  implicit lazy val arbConversationData: Arbitrary[ConversationData] = Arbitrary(for {
    id <- arbitrary[ConvId]
    remoteId <- arbitrary[RConvId]
    name <- arbitrary[Option[String]]
    creator <- arbitrary[UserId]
    convType <- arbitrary[ConversationType]
    lastEventTime <- arbitrary[Instant]
    lastEvent <- arbitrary[EventId]
    status <- arbitrary[Int]
    statusTime <- arbitrary[Instant]
    muted <- arbitrary[Boolean]
    muteTime <- arbitrary[Instant]
    archived <- arbitrary[Boolean]
    archiveTime <- arbitrary[Instant]
    cleared <- arbitrary[Instant]
    generatedName <- arbitrary[String]
    searchKey = name map SearchKey
    unreadCount <- posNum[Int]
    failedCount <- posNum[Int]
    hasVoice <- arbitrary[Boolean]
    unjoinedCall <- arbitrary[Boolean]
    missedCall <- arbitrary[Option[MessageId]]
    incomingKnock <- arbitrary[Option[MessageId]]
    renameEvent <- arbitrary[Option[EventId]]
    voiceMuted <- arbitrary[Boolean]
    hidden <- arbitrary[Boolean]
  } yield ConversationData(id, remoteId, name, creator, convType, lastEventTime, lastEvent, status, statusTime, Instant.EPOCH, muted, muteTime, archived, archiveTime, cleared, generatedName, searchKey, unreadCount, failedCount, hasVoice, unjoinedCall, missedCall, incomingKnock, renameEvent, voiceMuted, hidden))

  implicit lazy val arbUserData: Arbitrary[UserData] = Arbitrary(for {
    id <- arbitrary[UserId]
    name <- arbitrary[String]
    email <- arbitrary[Option[EmailAddress]]
    phone <- arbitrary[Option[PhoneNumber]]
    trackingId <- arbitrary[Option[TrackingId]]
    picture <- arbitrary[Option[AssetId]]
    accent <- arbitrary[Int]
    searchKey = SearchKey(name)
    connection <- arbitrary[ConnectionStatus]
    connectionLastUpdated <- arbitrary[Date]
    connectionMessage <- arbitrary[Option[String]]
    conversation <- arbitrary[Option[RConvId]]
    relation <- arbitrary[Relation]
    excludeFromPymk <- arbitrary[Boolean]
    syncTimestamp <- posNum[Long]
    displayName <- arbitrary[String]
  } yield UserData(id, name, email, phone, trackingId, picture, accent, searchKey, connection, connectionLastUpdated, connectionMessage, conversation, relation, excludeFromPymk, syncTimestamp, displayName))

  implicit lazy val arbRevision: Arbitrary[Revision] = Arbitrary(resultOf(Revision))
  implicit lazy val arbCaptureDeviceData: Arbitrary[CaptureDeviceData] = Arbitrary(resultOf(CaptureDeviceData))
  implicit lazy val arbVideoCallData: Arbitrary[VideoCallData] = Arbitrary(resultOf(VideoCallData))
  implicit lazy val arbCallTrackingData: Arbitrary[CallTrackingData] = Arbitrary(resultOf(CallTrackingData))
  implicit lazy val arbVoiceChannelData: Arbitrary[VoiceChannelData] = Arbitrary(resultOf(VoiceChannelData) map { data => data.copy(participantsById = data.participantsById.values.map(p => p.userId -> p).toMap) })

  implicit lazy val arbVoiceParticipantData: Arbitrary[VoiceParticipantData] = Arbitrary(resultOf(VoiceParticipantData))

  implicit lazy val arbOpenGraphData: Arbitrary[OpenGraphData] = Arbitrary(resultOf(OpenGraphData))
  implicit lazy val arbMessageContent: Arbitrary[MessageContent] = Arbitrary(resultOf(MessageContent))
  implicit lazy val arbGenericMessage: Arbitrary[GenericMessage] = Arbitrary(for {
    id <- arbitrary[MessageId]
    content = Text("test", Map.empty, Nil) // TODO: implement actual generator
  } yield GenericMessage(id, content))

  implicit lazy val arbMessageData: Arbitrary[MessageData] = Arbitrary(resultOf(MessageData))

  implicit lazy val arbImageData: Arbitrary[ImageData] = Arbitrary(for {
    tag        <- alphaNumStr
    remoteId   <- arbitrary[Option[RAssetDataId]]
    mime       <- Gen.oneOf(Seq(Mime.Gif, Mime.Jpg, Mime.Png))
    size       <- posNum[Int]
    data       <- arbitrary[Option[Array[Byte]]].map(_.filter(_.nonEmpty).map(bytes => Base64.encodeToString(bytes, Base64.NO_CLOSE | Base64.NO_WRAP | Base64.NO_PADDING)))
    width      <- posNum[Int]
    height     <- posNum[Int]
    origWidth  <- posNum[Int]
    origHeight <- posNum[Int]
    sent       <- arbitrary[Boolean]
    hasUrl     <- arbitrary[Boolean]
    url        <- alphaNumStr.map(Some(_).filter(_ => remoteId.isEmpty || hasUrl))
  } yield ImageData(tag, mime, width, height, origWidth, origHeight, size, remoteId, data, sent, url))

  implicit lazy val arbImageAssetData: Arbitrary[ImageAssetData] = Arbitrary(for {
    assetId    <- arbitrary[AssetId]
    convId     <- arbitrary[RConvId]
    versions   <- arbitrary[Seq[ImageData]]
  } yield ImageAssetData(assetId, convId, versions.sorted))

  implicit lazy val arbAnyAssetData: Arbitrary[AnyAssetData] = Arbitrary(for {
    id      <- arbitrary[AssetId]
    convId  <- arbitrary[RConvId]
    mime    <- oneOf(knownMimeTypes)
    size    <- posNum[Long]
    name    <- optGen(alphaNumStr)
    meta    <- arbitrary[Option[AssetMetaData]]
    preview <- arbitrary[Option[AssetPreviewData]]
    source  <- optGen(arbitrary[Uri])
    oMime   <- optGen(oneOf(knownMimeTypes))
    status  <- arbitrary[AssetStatus]
    time    <- arbitrary[Instant]
  } yield AnyAssetData(id, convId, mime, size, name, meta, preview, source, oMime, status, time))

  implicit lazy val arbAssetStatus: Arbitrary[AssetStatus] = Arbitrary(frequency((2, oneOf[AssetStatus](AssetStatus.UploadNotStarted,
    AssetStatus.MetaDataSent, AssetStatus.PreviewSent, AssetStatus.UploadInProgress, AssetStatus.UploadCancelled, AssetStatus.UploadFailed)),
    (1, oneOf[AssetStatus](resultOf(AssetStatus.UploadDone), resultOf(AssetStatus.DownloadFailed)))))
  implicit lazy val arbSyncableAssetStatus: Arbitrary[AssetStatus.Syncable] = Arbitrary(oneOf(AssetStatus.UploadCancelled, AssetStatus.UploadFailed))
  implicit lazy val arbRemoteKey: Arbitrary[RemoteKey] = Arbitrary(resultOf(RemoteKey))
  implicit lazy val arbAssetToken: Arbitrary[AssetToken] = Arbitrary(resultOf(AssetToken))
  implicit lazy val arbAssetKey: Arbitrary[AssetKey] = Arbitrary(resultOf(AssetKey))
  implicit lazy val arbOtrKey: Arbitrary[AESKey] = Arbitrary(sideEffect(AESKey()))
  implicit lazy val arbSha256: Arbitrary[Sha256] = Arbitrary(arbitrary[Array[Byte]].map(b => Sha256(sha2(b))))
  implicit lazy val arbLoudness: Arbitrary[AssetPreviewData.Loudness] = Arbitrary(listOfN(100, Gen.chooseNum(0f, 1f)).map(l => AssetPreviewData.Loudness(l.toVector)))
  implicit lazy val arbAssetPreview: Arbitrary[AssetPreviewData] = Arbitrary(oneOf(resultOf(AssetPreviewData.Image), arbLoudness.arbitrary))

  object MediaAssets {
    implicit lazy val arbArtistData: Arbitrary[ArtistData] = Arbitrary(resultOf(ArtistData))
    implicit lazy val arbTrackData: Arbitrary[TrackData] = Arbitrary(resultOf(TrackData))
    implicit lazy val arbPlaylistData: Arbitrary[PlaylistData] = Arbitrary(resultOf(PlaylistData))
    implicit lazy val arbEmptyMediaAssetData: Arbitrary[EmptyMediaAssetData] = Arbitrary(resultOf(EmptyMediaAssetData))

    implicit lazy val arbMediaAssetData: Arbitrary[MediaAssetData] = Arbitrary(oneOf(arbitrary[TrackData], arbitrary[PlaylistData], arbitrary[EmptyMediaAssetData]))
  }

  object SyncRequests {
    implicit lazy val arbSyncJob: Arbitrary[SyncJob] = Arbitrary(for {
      id <- arbitrary[SyncId]
      req <- arbitrary[SyncRequest]
    } yield SyncJob(id, req))

    implicit lazy val arbSyncRequest: Arbitrary[SyncRequest] = Arbitrary(oneOf(
      arbSimpleSyncRequest.arbitrary,
      arbitrary[RequestForUser],
      arbitrary[RequestForConversation],
      arbitrary[SyncUser],
      arbitrary[SyncConversation],
      arbitrary[SyncSearchQuery],
      arbitrary[SyncRichMedia],
      arbitrary[PostSelf],
      arbitrary[PostSelfPicture],
      arbitrary[PostConvJoin],
      arbitrary[PostConvLeave],
      arbitrary[DeleteGcmToken],
      arbitrary[PostAddressBook],
      arbitrary[PostInvitation],
      arbitrary[SyncPreKeys]))

    implicit lazy val arbUserBasedSyncRequest: Arbitrary[RequestForUser] = Arbitrary(oneOf(
      arbitrary[SyncCommonConnections],
      arbitrary[PostExcludePymk],
      arbitrary[PostConnection],
      arbitrary[PostConnectionStatus]))

    implicit lazy val arbConvBasedSyncRequest: Arbitrary[RequestForConversation] = Arbitrary(oneOf(
      arbitrary[SyncCallState],
      arbitrary[PostConv],
      arbitrary[PostConvName],
      arbitrary[PostConvState],
      arbitrary[PostLastRead],
      arbitrary[PostTypingState],
      arbitrary[PostMessage],
      arbitrary[PostDeleted],
      arbitrary[PostLiking],
      arbitrary[PostAssetStatus]))

    lazy val arbSimpleSyncRequest: Arbitrary[SyncRequest] = Arbitrary(oneOf(SyncSelf, DeleteAccount, SyncConversations, SyncConnections, SyncConnectedUsers, RegisterGcmToken))

    implicit lazy val arbUsersSyncRequest: Arbitrary[SyncUser] = Arbitrary(listOf(arbitrary[UserId]) map { u => SyncUser(u.toSet) })
    implicit lazy val arbConvsSyncRequest: Arbitrary[SyncConversation] = Arbitrary(listOf(arbitrary[ConvId]) map { c => SyncConversation(c.toSet) })
    implicit lazy val arbSearchSyncRequest: Arbitrary[SyncSearchQuery] = Arbitrary(resultOf(SyncSearchQuery))
    implicit lazy val arbSelfPictureSyncRequest: Arbitrary[PostSelfPicture] = Arbitrary(resultOf(PostSelfPicture))
    implicit lazy val arbRichMediaSyncRequest: Arbitrary[SyncRichMedia] = Arbitrary(resultOf(SyncRichMedia))
    implicit lazy val arbGcmSyncRequest: Arbitrary[DeleteGcmToken] = Arbitrary(resultOf(DeleteGcmToken))
    implicit lazy val arbCallStateSyncRequest: Arbitrary[SyncCallState] = Arbitrary(resultOf(SyncCallState))
    implicit lazy val arbPostConvSyncRequest: Arbitrary[PostConv] = Arbitrary(resultOf(PostConv))
    implicit lazy val arbPostLastReadRequest: Arbitrary[PostLastRead] = Arbitrary(resultOf(PostLastRead))
    implicit lazy val arbPostConvNameSyncRequest: Arbitrary[PostConvName] = Arbitrary(resultOf(PostConvName))
    implicit lazy val arbPostSelfSyncRequest: Arbitrary[PostSelf] = Arbitrary(resultOf(PostSelf))
    implicit lazy val arbPostConvStateSyncRequest: Arbitrary[PostConvState] = Arbitrary(resultOf(PostConvState))
    implicit lazy val arbPostTypingStateSyncRequest: Arbitrary[PostTypingState] = Arbitrary(resultOf(PostTypingState))
    implicit lazy val arbCommonConnectionsSyncRequest: Arbitrary[SyncCommonConnections] = Arbitrary(resultOf(SyncCommonConnections))
    implicit lazy val arbPostConnectionStatusSyncRequest: Arbitrary[PostConnectionStatus] = Arbitrary(resultOf(PostConnectionStatus))
    implicit lazy val arbPostExcludePymkSyncRequest: Arbitrary[PostExcludePymk] = Arbitrary(resultOf(PostExcludePymk))
    implicit lazy val arbMessageSyncRequest: Arbitrary[PostMessage] = Arbitrary(resultOf(PostMessage))
    implicit lazy val arbMessageDelSyncRequest: Arbitrary[PostDeleted] = Arbitrary(resultOf(PostDeleted))
    implicit lazy val arbPostConvJoinSyncRequest: Arbitrary[PostConvJoin] = Arbitrary(resultOf(PostConvJoin))
    implicit lazy val arbPostConvLeaveSyncRequest: Arbitrary[PostConvLeave] = Arbitrary(resultOf(PostConvLeave))
    implicit lazy val arbConnectionSyncRequest: Arbitrary[PostConnection] = Arbitrary(resultOf(PostConnection))
    implicit lazy val arbAddressBookSyncRequest: Arbitrary[PostAddressBook] = Arbitrary(resultOf(PostAddressBook))
    implicit lazy val arbInvitationSyncRequest: Arbitrary[PostInvitation] = Arbitrary(resultOf(PostInvitation))
    implicit lazy val arbPostLiking: Arbitrary[PostLiking] = Arbitrary(resultOf(PostLiking))
    implicit lazy val arbSyncPreKey: Arbitrary[SyncPreKeys] = Arbitrary(resultOf(SyncPreKeys))
    implicit lazy val arbPostAssetStatus: Arbitrary[PostAssetStatus] = Arbitrary(resultOf(PostAssetStatus))
  }

  implicit lazy val arbCallSequenceNumber: Arbitrary[CallSequenceNumber] = Arbitrary(choose(2, 999999) map CallSequenceNumber)

  implicit lazy val arbUid: Arbitrary[Uid] = Arbitrary(sideEffect(Uid()))
  implicit lazy val arbConvId: Arbitrary[ConvId] = Arbitrary(sideEffect(ConvId()))
  implicit lazy val arbRConvId: Arbitrary[RConvId] = Arbitrary(sideEffect(RConvId()))
  implicit lazy val arbUserId: Arbitrary[UserId] = Arbitrary(sideEffect(UserId()))
  implicit lazy val arbRAssetDataId: Arbitrary[RAssetDataId] = Arbitrary(sideEffect(RAssetDataId()))
  implicit lazy val arbAssetId: Arbitrary[AssetId] = Arbitrary(sideEffect(AssetId()))
  implicit lazy val arbSyncId: Arbitrary[SyncId] = Arbitrary(sideEffect(SyncId()))
  implicit lazy val arbGcmId: Arbitrary[GcmId] = Arbitrary(sideEffect(GcmId()))
  implicit lazy val arbMessageId: Arbitrary[MessageId] = Arbitrary(sideEffect(MessageId()))
  implicit lazy val arbTrackingId: Arbitrary[TrackingId] = Arbitrary(sideEffect(TrackingId()))
  implicit lazy val arbContactId: Arbitrary[ContactId] = Arbitrary(sideEffect(ContactId()))
  implicit lazy val arbCallSessionId: Arbitrary[CallSessionId] = Arbitrary(sideEffect(CallSessionId()))
  implicit lazy val arbClientId: Arbitrary[ClientId] = Arbitrary(sideEffect(ClientId()))

  implicit lazy val arbDate: Arbitrary[Date] = Arbitrary(choose(0L, 999999L).map(i => new Date(currentTimeMillis - 1000000000L + i * 1000L)))
  implicit lazy val arbInstant: Arbitrary[Instant] = Arbitrary(posNum[Long] map Instant.ofEpochMilli)
  implicit lazy val arbDuration: Arbitrary[Duration] = Arbitrary(posNum[Long] map Duration.ofMillis)
  implicit lazy val arbFiniteDuration: Arbitrary[FiniteDuration] = Arbitrary(posNum[Long] map (FiniteDuration(_, TimeUnit.MILLISECONDS)))

  implicit lazy val arbLiking: Arbitrary[Liking] = Arbitrary(resultOf(Liking.apply _))
  implicit lazy val arbLikingAction: Arbitrary[Liking.Action] = Arbitrary(oneOf(Liking.Action.values.toSeq))
  implicit lazy val arbMessageAndLikes: Arbitrary[MessageAndLikes] = Arbitrary(for {
    msg <- arbitrary[MessageData]
    self <- arbitrary[UserId]
    others <- listOf(arbitrary[UserId])
    includeSelf <- frequency((4, false), (1, true))
    ids = if (includeSelf) self :: others else others
  } yield MessageAndLikes(msg, ids.toVector, includeSelf))

  implicit lazy val arbEventId: Arbitrary[EventId] = Arbitrary(for {
    seq <- posNum[Long]
    hex <- arbitrary[Long] map EventId.hexString
  } yield EventId(seq, hex))

  implicit lazy val arbMetaData: Arbitrary[AssetMetaData] = Arbitrary(oneOf(arbImageMetaData.arbitrary, arbVideoMetaData.arbitrary, arbAudioMetaData.arbitrary))
  implicit lazy val arbImageMetaData: Arbitrary[AssetMetaData.Image] = Arbitrary(for (d <- arbitrary[Dim2]; t <- optGen(oneOf(ImageData.Tag.Medium, ImageData.Tag.MediumPreview, ImageData.Tag.Preview, ImageData.Tag.SmallProfile))) yield AssetMetaData.Image(d, t))
  implicit lazy val arbVideoMetaData: Arbitrary[AssetMetaData.Video] = Arbitrary(resultOf(AssetMetaData.Video(_: Dim2, _: Duration)))
  implicit lazy val arbAudioMetaData: Arbitrary[AssetMetaData.Audio] = Arbitrary(resultOf(AssetMetaData.Audio(_: Duration)))
  implicit lazy val arbDim2: Arbitrary[Dim2] = Arbitrary(for (w <- genDimension; h <- genDimension) yield Dim2(w, h))
  lazy val genDimension = chooseNum(0, 10000)

  implicit def optGen[T](implicit gen: Gen[T]): Gen[Option[T]] = frequency((1, Gen.const(None)), (2, gen.map(Some(_))))

  implicit lazy val arbUserInfo: Arbitrary[UserInfo] = Arbitrary(for {
    userId <- arbitrary[UserId]
    name <- optGen(alphaNumStr)
    email <- arbitrary[Option[EmailAddress]]
    phone <- arbitrary[Option[PhoneNumber]]
    picture <- arbitrary[Option[ImageAssetData]]
    trackingId <- arbitrary[Option[TrackingId]]
    accent <- arbitrary[Option[Int]]
  } yield UserInfo(userId, name, accent, email, phone, picture, trackingId))

  implicit lazy val arbAddressBook: Arbitrary[AddressBook] = Arbitrary(for {
    selfHashes <- arbitrary[Seq[String]] map (_ map sha2)
    contacts   <- arbitrary[Seq[AddressBook.ContactHashes]]
  } yield AddressBook(selfHashes, contacts))

  implicit lazy val arbContactData: Arbitrary[AddressBook.ContactHashes] = Arbitrary(for {
    id     <- arbitrary[String] map sha2
    hashes <- arbitrary[Set[String]] map (_ map sha2)
  } yield AddressBook.ContactHashes(ContactId(id), hashes))

  implicit lazy val arbConvState: Arbitrary[ConversationState] = Arbitrary(resultOf(
    ConversationState(_: Option[Boolean], _: Option[Instant], _: Option[Boolean], _: Option[Instant])))

  implicit lazy val genIncreasingEventId: Gen[EventId] = sideEffect(EventId(serialCounter.getAndIncrement))
  lazy val serialCounter: AtomicLong = new AtomicLong()

  implicit lazy val arbToken: Arbitrary[Token] = Arbitrary(resultOf(Token))

  implicit lazy val arbEmailAddress: Arbitrary[EmailAddress] = Arbitrary(resultOf(EmailAddress))
  implicit lazy val arbPhoneNumber: Arbitrary[PhoneNumber] = Arbitrary(resultOf(PhoneNumber))
  implicit lazy val arbInvitation: Arbitrary[Invitation] = Arbitrary(resultOf(Invitation))
  implicit lazy val ArbLocale: Arbitrary[Locale] = Arbitrary(oneOf(availableLocales))
  implicit lazy val ArbGenericToken: Arbitrary[Invitations.GenericToken] = Arbitrary(resultOf(InvitationTokenFactory.genericTokenFromCode _))
  implicit lazy val ArbPersonalToken: Arbitrary[Invitations.PersonalToken] = Arbitrary(resultOf(InvitationTokenFactory.personalTokenFromCode _))

  def sideEffect[A](f: => A): Gen[A] = resultOf[Unit, A](_ => f)

  lazy val availableLocales: Vector[Locale] = Locale.getAvailableLocales.flatMap(l => bcp47.localeFor(bcp47.languageTagOf(l))).distinct.toVector
}
