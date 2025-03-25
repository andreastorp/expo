@file:OptIn(EitherType::class)

package expo.modules.video

import android.net.Uri
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import com.facebook.react.common.annotations.UnstableReactNativeAPI
import expo.modules.kotlin.apifeatures.EitherType
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.types.Either
import expo.modules.video.enums.AudioMixingMode
import expo.modules.video.enums.ContentFit
import expo.modules.video.player.VideoPlayer
import expo.modules.video.records.BufferOptions
import expo.modules.video.records.SubtitleTrack
import expo.modules.video.records.VideoSource
import expo.modules.video.records.VideoThumbnailOptions
import expo.modules.video.utils.runWithPiPMisconfigurationSoftHandling
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration

// https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide#improvements_in_media3
@UnstableReactNativeAPI
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoVideo")

    OnCreate {
      VideoManager.onModuleCreated(appContext)
    }

    Function("isPictureInPictureSupported") {
      VideoView.isPictureInPictureSupported(appContext.throwingActivity)
    }

    Function("getCurrentVideoCacheSize") {
      VideoManager.cache.getCurrentCacheSize()
    }

    AsyncFunction("setVideoCacheSizeAsync") { size: Long ->
      VideoManager.cache.setMaxCacheSize(size)
    }

    AsyncFunction("clearVideoCacheAsync") {
      VideoManager.cache.clear()
    }

    View(VideoView::class) {
      Events(
        "onPictureInPictureStart",
        "onPictureInPictureStop",
        "onFullscreenEnter",
        "onFullscreenExit",
        "onFirstFrameRender"
      )

      Prop("player") { view: VideoView, player: VideoPlayer ->
        view.videoPlayer = player
      }

      Prop("nativeControls") { view: VideoView, useNativeControls: Boolean ->
        view.useNativeControls = useNativeControls
      }

      Prop("contentFit") { view: VideoView, contentFit: ContentFit ->
        view.contentFit = contentFit
      }

      Prop("startsPictureInPictureAutomatically") { view: VideoView, autoEnterPiP: Boolean ->
        view.autoEnterPiP = autoEnterPiP
      }

      Prop("allowsFullscreen") { view: VideoView, allowsFullscreen: Boolean? ->
        view.allowsFullscreen = allowsFullscreen ?: true
      }

      Prop("requiresLinearPlayback") { view: VideoView, requiresLinearPlayback: Boolean? ->
        val linearPlayback = requiresLinearPlayback ?: false
        view.playerView.applyRequiresLinearPlayback(linearPlayback)
        view.videoPlayer?.requiresLinearPlayback = linearPlayback
      }

      AsyncFunction("enterFullscreen") { view: VideoView ->
        view.enterFullscreen()
      }

      AsyncFunction("exitFullscreen") {
        throw MethodUnsupportedException("exitFullscreen")
      }

      AsyncFunction("startPictureInPicture") { view: VideoView ->
        runWithPiPMisconfigurationSoftHandling(true) {
          view.enterPictureInPicture()
        }
      }

      AsyncFunction("stopPictureInPicture") {
        throw MethodUnsupportedException("stopPictureInPicture")
      }

      OnViewDestroys {
        VideoManager.unregisterVideoView(it)
        // Remove relations with mounted players
        it.videoPlayer = null
      }

      OnViewDidUpdateProps { view ->
        if (view.playerView.useController != view.useNativeControls) {
          view.playerView.useController = view.useNativeControls
        }
      }
    }

    Class(VideoPlayer::class) {
      Constructor { source: VideoSource? ->
        val player = VideoPlayer(appContext.throwingActivity.applicationContext, appContext, source)
        appContext.mainQueue.launch {
          player.prepare()
        }
        return@Constructor player
      }

      Property("playing")
        .get { ref: VideoPlayer ->
          ref.playing
        }

      Property("muted")
        .get { ref: VideoPlayer ->
          ref.muted
        }
        .set { ref: VideoPlayer, muted: Boolean ->
          appContext.mainQueue.launch {
            ref.muted = muted
          }
        }

      Property("volume")
        .get { ref: VideoPlayer ->
          ref.volume
        }
        .set { ref: VideoPlayer, volume: Float ->
          appContext.mainQueue.launch {
            ref.userVolume = volume
            ref.volume = volume
          }
        }

      Property("currentTime")
        .get { ref: VideoPlayer ->
          // TODO: we shouldn't block the thread, but there are no events for the player position change,
          //  so we can't update the currentTime in a non-blocking way like the other properties.
          //  Until we think of something better we can temporarily do it this way
          runBlocking(appContext.mainQueue.coroutineContext) {
            ref.player.currentPosition / 1000f
          }
        }
        .set { ref: VideoPlayer, currentTime: Double ->
          appContext.mainQueue.launch {
            ref.player.seekTo((currentTime * 1000).toLong())
          }
        }

      Property("currentLiveTimestamp")
        .get { ref: VideoPlayer ->
          runBlocking(appContext.mainQueue.coroutineContext) {
            ref.currentLiveTimestamp
          }
        }

      Property("availableVideoTracks")
        .get { ref: VideoPlayer ->
          ref.availableVideoTracks
        }

      Property("videoTrack")
        .get { ref: VideoPlayer ->
          ref.currentVideoTrack
        }

      Property("availableSubtitleTracks")
        .get { ref: VideoPlayer ->
          ref.subtitles.availableSubtitleTracks
        }

      Property("subtitleTrack")
        .get { ref: VideoPlayer ->
          ref.subtitles.currentSubtitleTrack
        }
        .set { ref: VideoPlayer, subtitleTrack: SubtitleTrack? ->
          appContext.mainQueue.launch {
            ref.subtitles.currentSubtitleTrack = subtitleTrack
          }
        }

      Property("currentOffsetFromLive")
        .get { ref: VideoPlayer ->
          runBlocking(appContext.mainQueue.coroutineContext) {
            ref.currentOffsetFromLive
          }
        }

      Property("duration")
        .get { ref: VideoPlayer ->
          ref.duration
        }

      Property("playbackRate")
        .get { ref: VideoPlayer ->
          ref.playbackParameters.speed
        }
        .set { ref: VideoPlayer, playbackRate: Float ->
          appContext.mainQueue.launch {
            val pitch = if (ref.preservesPitch) 1f else playbackRate
            ref.playbackParameters = PlaybackParameters(playbackRate, pitch)
          }
        }

      Property("isLive")
        .get { ref: VideoPlayer ->
          ref.isLive
        }

      Property("preservesPitch")
        .get { ref: VideoPlayer ->
          ref.preservesPitch
        }
        .set { ref: VideoPlayer, preservesPitch: Boolean ->
          appContext.mainQueue.launch {
            ref.preservesPitch = preservesPitch
          }
        }

      Property("showNowPlayingNotification")
        .get { ref: VideoPlayer ->
          ref.showNowPlayingNotification
        }
        .set { ref: VideoPlayer, showNotification: Boolean ->
          appContext.mainQueue.launch {
            ref.showNowPlayingNotification = showNotification
          }
        }

      Property("status")
        .get { ref: VideoPlayer ->
          ref.status
        }

      Property("staysActiveInBackground")
        .get { ref: VideoPlayer ->
          ref.staysActiveInBackground
        }
        .set { ref: VideoPlayer, staysActive: Boolean ->
          ref.staysActiveInBackground = staysActive
        }

      Property("loop")
        .get { ref: VideoPlayer ->
          ref.player.repeatMode == REPEAT_MODE_ONE
        }
        .set { ref: VideoPlayer, loop: Boolean ->
          appContext.mainQueue.launch {
            ref.player.repeatMode = if (loop) {
              REPEAT_MODE_ONE
            } else {
              REPEAT_MODE_OFF
            }
          }
        }

      Property("bufferedPosition")
        .get { ref: VideoPlayer ->
          // Same as currentTime
          runBlocking(appContext.mainQueue.coroutineContext) {
            ref.bufferedPosition
          }
        }

      Property("bufferOptions")
        .get { ref: VideoPlayer ->
          ref.bufferOptions
        }
        .set { ref: VideoPlayer, bufferOptions: BufferOptions ->
          ref.bufferOptions = bufferOptions
        }

      Function("play") { ref: VideoPlayer ->
        appContext.mainQueue.launch {
          ref.player.play()
        }
      }

      Function("pause") { ref: VideoPlayer ->
        appContext.mainQueue.launch {
          ref.player.pause()
        }
      }

      Property("timeUpdateEventInterval")
        .get { ref: VideoPlayer ->
          ref.intervalUpdateClock.interval / 1000.0
        }
        .set { ref: VideoPlayer, intervalSeconds: Float ->
          ref.intervalUpdateClock.interval = (intervalSeconds * 1000).toLong()
        }

      Property("audioMixingMode")
        .get { ref: VideoPlayer ->
          ref.audioMixingMode
        }
        .set { ref: VideoPlayer, audioMixingMode: AudioMixingMode ->
          appContext.mainQueue.launch {
            ref.audioMixingMode = audioMixingMode
          }
        }

      Function("replace") { ref: VideoPlayer, source: Either<Uri, VideoSource>? ->
        val videoSource = source?.let {
          if (it.`is`(VideoSource::class)) {
            it.get(VideoSource::class)
          } else {
            VideoSource(it.get(Uri::class))
          }
        }

        appContext.mainQueue.launch {
          ref.uncommittedSource = videoSource
          ref.prepare()
        }
      }

      Function("seekBy") { ref: VideoPlayer, seekTime: Double ->
        appContext.mainQueue.launch {
          val seekPos = ref.player.currentPosition + (seekTime * 1000).toLong()
          ref.player.seekTo(seekPos)
        }
      }

      Function("replay") { ref: VideoPlayer ->
        appContext.mainQueue.launch {
          ref.player.seekTo(0)
          ref.player.play()
        }
      }

      AsyncFunction("generateThumbnailsAsync") Coroutine { ref: VideoPlayer, times: List<Duration>, options: VideoThumbnailOptions? ->
        return@Coroutine ref.toMetadataRetriever().safeUse {
          val bitmaps = times.map { time ->
            appContext.backgroundCoroutineScope.async {
              generateThumbnailAtTime(time, options)
            }
          }

          bitmaps.awaitAll()
        }
      }

      Class<VideoThumbnail> {
        Property("width") { ref -> ref.width }
        Property("height") { ref -> ref.height }
        Property("requestedTime") { ref -> ref.requestedTime }
        Property("actualTime") { ref -> ref.actualTime }
      }
    }

    OnActivityEntersForeground {
      VideoManager.onAppForegrounded()
    }

    OnActivityEntersBackground {
      VideoManager.onAppBackgrounded()
    }
  }
}
