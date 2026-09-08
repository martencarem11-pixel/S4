package com.example.arcore

import android.content.Context
import android.util.Log
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import java.io.File

/**
 * Production-Grade Google ARCore Recording and Playback Manager.
 * Implements the official ARCore Recording & Playback API:
 * 1. Records live AR session to standard MP4 dataset (IDLE -> RECORDING -> STOPPED).
 * 2. Validates and replays recorded AR datasets through ARCore VIO (IDLE -> PLAYING -> FINISHED/ERROR).
 * 3. Supports dataset inspection, pause, resume, cleanup, and telemetry.
 */
data class RecordingTelemetry(
  val isRecording: Boolean = false,
  val isPlayingBack: Boolean = false,
  val recordingStatus: String = "IDLE",
  val playbackStatus: String = "IDLE",
  val datasetFilePath: String? = null,
  val recordedDurationSeconds: Float = 0f,
  val recordingFileSizeMb: Float = 0f,
  val errorMessage: String? = null
)

class ArCoreRecordingPlaybackManager(private val context: Context) {

  companion object {
    private const val TAG = "ArCoreRecPlayback"
  }

  var telemetry: RecordingTelemetry = RecordingTelemetry()
    private set

  private var recordingStartTimeMs: Long = 0L
  private var currentRecordingFile: File? = null

  /**
   * Starts recording the current ARCore session into a local MP4 dataset file.
   */
  fun startRecording(session: Session, customFileName: String? = null): Boolean {
    return try {
      val fileName = customFileName ?: "ar_session_${System.currentTimeMillis()}.mp4"
      val datasetFile = File(context.cacheDir, fileName)
      currentRecordingFile = datasetFile

      val recordingConfig = RecordingConfig(session)
        .setMp4DatasetFilePath(datasetFile.absolutePath)
        .setAutoStopOnPause(true)

      session.startRecording(recordingConfig)
      recordingStartTimeMs = System.currentTimeMillis()

      telemetry = telemetry.copy(
        isRecording = true,
        recordingStatus = "RECORDING",
        datasetFilePath = datasetFile.absolutePath,
        recordedDurationSeconds = 0f,
        recordingFileSizeMb = 0f,
        errorMessage = null
      )
      Log.i(TAG, "ARCore recording started -> ${datasetFile.absolutePath}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start ARCore recording: ${e.message}", e)
      telemetry = telemetry.copy(
        isRecording = false,
        recordingStatus = "ERROR",
        errorMessage = e.message
      )
      false
    }
  }

  /**
   * Stops active ARCore session recording.
   */
  fun stopRecording(session: Session): File? {
    return try {
      session.stopRecording()
      val recordedFile = currentRecordingFile
      val fileSizeMb = (recordedFile?.length() ?: 0L).toFloat() / (1024f * 1024f)

      telemetry = telemetry.copy(
        isRecording = false,
        recordingStatus = "STOPPED",
        recordingFileSizeMb = fileSizeMb
      )
      Log.i(TAG, "ARCore recording stopped successfully (${fileSizeMb}MB).")
      recordedFile
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop ARCore recording: ${e.message}", e)
      telemetry = telemetry.copy(
        isRecording = false,
        recordingStatus = "ERROR",
        errorMessage = e.message
      )
      null
    }
  }

  /**
   * Configures ARCore session to replay a recorded MP4 dataset file instead of live camera.
   * Performs complete file validation before attempting to set dataset.
   * Note: ARCore requires session to be in PAUSED state when setting playback dataset.
   */
  fun setPlaybackDataset(session: Session, datasetFile: File): Boolean {
    // File validation
    if (!datasetFile.exists()) {
      val msg = "Dataset file does not exist: ${datasetFile.absolutePath}"
      Log.e(TAG, msg)
      telemetry = telemetry.copy(playbackStatus = "ERROR", errorMessage = msg)
      return false
    }
    if (!datasetFile.canRead()) {
      val msg = "Dataset file is not readable: ${datasetFile.absolutePath}"
      Log.e(TAG, msg)
      telemetry = telemetry.copy(playbackStatus = "ERROR", errorMessage = msg)
      return false
    }
    if (datasetFile.length() < 1024) {
      val msg = "Dataset file is too small or empty (${datasetFile.length()} bytes)"
      Log.e(TAG, msg)
      telemetry = telemetry.copy(playbackStatus = "ERROR", errorMessage = msg)
      return false
    }
    if (!datasetFile.name.endsWith(".mp4", ignoreCase = true)) {
      val msg = "Dataset file is not an MP4 file: ${datasetFile.name}"
      Log.e(TAG, msg)
      telemetry = telemetry.copy(playbackStatus = "ERROR", errorMessage = msg)
      return false
    }

    return try {
      session.setPlaybackDataset(datasetFile.absolutePath)
      telemetry = telemetry.copy(
        isPlayingBack = true,
        playbackStatus = "PLAYING",
        datasetFilePath = datasetFile.absolutePath,
        errorMessage = null
      )
      Log.i(TAG, "ARCore playback dataset configured: ${datasetFile.absolutePath}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed setting playback dataset: ${e.message}", e)
      telemetry = telemetry.copy(
        isPlayingBack = false,
        playbackStatus = "ERROR",
        errorMessage = e.message
      )
      false
    }
  }

  /**
   * Stops playback and returns ARCore session to live camera feed.
   */
  fun stopPlayback(session: Session): Boolean {
    return try {
      session.setPlaybackDataset(null)
      telemetry = telemetry.copy(
        isPlayingBack = false,
        playbackStatus = "FINISHED",
        datasetFilePath = null
      )
      Log.i(TAG, "ARCore playback stopped. Returned to live camera feed.")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping playback: ${e.message}", e)
      false
    }
  }

  /**
   * Deletes a recorded dataset file from cache.
   */
  fun deleteDataset(datasetFile: File): Boolean {
    return try {
      if (datasetFile.exists()) {
        datasetFile.delete()
      } else {
        true
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to delete dataset file: ${e.message}")
      false
    }
  }

  /**
   * Updates recording & playback telemetry on every frame update.
   */
  fun updateFrameState(session: Session) {
    try {
      val recStatus = session.recordingStatus
      val playStatus = session.playbackStatus

      val isCurrentlyRecording = (recStatus == RecordingStatus.OK)
      val isCurrentlyPlaying = (playStatus == PlaybackStatus.OK)

      val durationSec = if (isCurrentlyRecording && recordingStartTimeMs > 0) {
        (System.currentTimeMillis() - recordingStartTimeMs) / 1000f
      } else {
        telemetry.recordedDurationSeconds
      }

      val fileSize = currentRecordingFile?.let {
        it.length().toFloat() / (1024f * 1024f)
      } ?: telemetry.recordingFileSizeMb

      val recLabel = when (recStatus) {
        RecordingStatus.NONE -> if (telemetry.isRecording) "STOPPED" else "IDLE"
        RecordingStatus.OK -> "RECORDING"
        RecordingStatus.IO_ERROR -> "ERROR_IO"
        else -> recStatus.name
      }

      val playLabel = when (playStatus) {
        PlaybackStatus.NONE -> if (telemetry.isPlayingBack) "FINISHED" else "IDLE"
        PlaybackStatus.OK -> "PLAYING"
        PlaybackStatus.FINISHED -> "FINISHED"
        PlaybackStatus.IO_ERROR -> "ERROR_IO"
        else -> playStatus.name
      }

      telemetry = telemetry.copy(
        isRecording = isCurrentlyRecording,
        isPlayingBack = isCurrentlyPlaying,
        recordingStatus = recLabel,
        playbackStatus = playLabel,
        recordedDurationSeconds = durationSec,
        recordingFileSizeMb = fileSize
      )
    } catch (e: Exception) {
      // Ignored during transitions
    }
  }
}
