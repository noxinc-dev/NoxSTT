// Copyright 2020 Ciaran O'Reilly
// Copyright 2019 Alpha Cephei Inc.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package cat.oreilly.localstt;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.vosk.android.RecognitionListener;
import org.mozilla.deepspeech.libdeepspeech.DeepSpeechModel;
import org.mozilla.deepspeech.libdeepspeech.DeepSpeechStreamingState;

import com.konovalov.vad.Vad;
import com.konovalov.vad.VadConfig;

/**
 * Service that records audio in a thread, passes it to a recognizer and emits
 * recognition results. Recognition events are passed to a client using
 * {@link RecognitionListener}
 *
 */
public class DeepSpeechService {

    protected static final String TAG = DeepSpeechService.class.getSimpleName();

    private final DeepSpeechModel model;
    private final DeepSpeechStreamingState streamContext;
    private final Vad vad;

    private final int sampleRate;
    private final static float BUFFER_SIZE_SECONDS = 0.4f;
    private int bufferSize;
    private final AudioRecord recorder;

    private Thread recognizerThread;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Collection<RecognitionListener> listeners = new HashSet<RecognitionListener>();

    /**
     * Creates speech service. Service holds the AudioRecord object, so you need to
     * call {@link release} in order to properly finalize it.
     * 
     * @throws IOException thrown if audio recorder can not be created for some
     *                     reason.
     */
    public DeepSpeechService(DeepSpeechModel model, float sampleRate) throws IOException {
        this.model = model;
        this.sampleRate = (int) sampleRate;
        this.streamContext = model.createStream();

        vad = new Vad(VadConfig.newBuilder().setSampleRate(VadConfig.SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(VadConfig.FrameSize.FRAME_SIZE_480).setMode(VadConfig.Mode.NORMAL).build());

        bufferSize = Math.round(this.sampleRate * BUFFER_SIZE_SECONDS);
        recorder = new AudioRecord(AudioSource.VOICE_RECOGNITION, this.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);

        if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED) {
            recorder.release();
            throw new IOException("Failed to initialize recorder. Microphone might be already in use.");
        }
        Log.i(TAG, "DeepSpeechService initialized");
    }

    /**
     * Adds listener.
     */
    public void addListener(RecognitionListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * Removes listener.
     */
    public void removeListener(RecognitionListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /**
     * Starts recognition. Does nothing if recognition is active.
     * 
     * @return true if recognition was actually started
     */
    public boolean startListening() {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread();
        recognizerThread.start();
        return true;
    }

    /**
     * Starts recognition. After specified timeout listening stops and the
     * endOfSpeech signals about that. Does nothing if recognition is active.
     * 
     * @timeout - timeout in milliseconds to listen.
     * 
     * @return true if recognition was actually started
     */
    public boolean startListening(int timeout) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(timeout);
        recognizerThread.start();
        return true;
    }

    private boolean stopRecognizerThread() {
        if (null == recognizerThread)
            return false;

        try {
            recognizerThread.interrupt();
            recognizerThread.join();
        } catch (InterruptedException e) {
            // Restore the interrupted status.
            Thread.currentThread().interrupt();
        }

        recognizerThread = null;
        return true;
    }

    /**
     * Stops recognition. All listeners should receive final result if there is any.
     * Does nothing if recognition is not active.
     * 
     * @return true if recognition was actually stopped
     */
    public boolean stop() {
        boolean result = stopRecognizerThread();
        if (result) {
            mainHandler.post(new ResultEvent(model.finishStream(streamContext), true));
        }
        return result;
    }

    /**
     * Cancels recognition. Listeners do not receive final result. Does nothing if
     * recognition is not active.
     * 
     * @return true if recognition was actually canceled
     */
    public boolean cancel() {
        Log.d(TAG, "#cancel");
        boolean result = stopRecognizerThread();
        this.model.freeModel(); // Reset recognizer state
        return result;
    }

    /**
     * Shutdown the recognizer and release the recorder
     */
    public void shutdown() {
        Log.d(TAG, "#shutdown");
        this.model.freeModel();
        recorder.release();
    }

    private final class RecognizerThread extends Thread {

        private int remainingSamples;
        private int timeoutSamples;
        private final static int NO_TIMEOUT = -1;

        public RecognizerThread(int timeout) {
            if (timeout != NO_TIMEOUT)
                this.timeoutSamples = timeout * sampleRate / 1000;
            else
                this.timeoutSamples = NO_TIMEOUT;
            this.remainingSamples = this.timeoutSamples;
        }

        public RecognizerThread() {
            this(NO_TIMEOUT);
        }

        @Override
        public void run() {
            Log.i(TAG, "Start Recording...");

            vad.start();
            recorder.startRecording();
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
                recorder.stop();
                IOException ioe = new IOException("Failed to start recording. Microphone might be already in use.");
                mainHandler.post(new OnErrorEvent(ioe));
                return;
            }

            short[] buffer = new short[bufferSize];
            int nread = recorder.read(buffer, 0, buffer.length);
            boolean speechDetected = false;
            boolean feedAudio = true;

            while (!interrupted() && ((timeoutSamples == NO_TIMEOUT) || (remainingSamples > 0)) && feedAudio) {

                if (nread < 0) {
                    throw new RuntimeException("error reading audio buffer");
                } else {
                    Log.i(TAG, "Feeding audio");
                    model.feedAudioContent(streamContext, buffer, nread);
                    boolean isSpeech = vad.isSpeech(buffer);
                    if (isSpeech) {
                        Log.d(TAG, "Speech detected");
                        speechDetected = true;
                    }
                    if (speechDetected && !isSpeech) {
                        Log.d(TAG, "Silence detected");
                        feedAudio = false;
                    }

                }

                if (timeoutSamples != NO_TIMEOUT) {
                    remainingSamples = remainingSamples - nread;
                }
                nread = recorder.read(buffer, 0, buffer.length);
            }

            mainHandler.post(new ResultEvent(model.finishStream(streamContext), true));

            recorder.stop();
            vad.stop();

            // Remove all pending notifications.
            mainHandler.removeCallbacksAndMessages(null);

            // If we met timeout signal that speech ended
            if (timeoutSamples != NO_TIMEOUT && remainingSamples <= 0) {
                mainHandler.post(new TimeoutEvent());
            }
        }
    }

    private abstract class RecognitionEvent implements Runnable {
        public void run() {
            RecognitionListener[] emptyArray = new RecognitionListener[0];
            for (RecognitionListener listener : listeners.toArray(emptyArray))
                execute(listener);
        }

        protected abstract void execute(RecognitionListener listener);
    }

    private class ResultEvent extends RecognitionEvent {
        protected final String hypothesis;
        private final boolean finalResult;

        ResultEvent(String hypothesis, boolean finalResult) {
            this.hypothesis = hypothesis;
            this.finalResult = finalResult;
        }

        @Override
        protected void execute(RecognitionListener listener) {
            if (finalResult)
                listener.onResult(hypothesis);
            else
                listener.onPartialResult(hypothesis);
        }
    }

    private class OnErrorEvent extends RecognitionEvent {
        private final Exception exception;

        OnErrorEvent(Exception exception) {
            this.exception = exception;
        }

        @Override
        protected void execute(RecognitionListener listener) {
            listener.onError(exception);
        }
    }

    private class TimeoutEvent extends RecognitionEvent {
        @Override
        protected void execute(RecognitionListener listener) {
            listener.onTimeout();
        }
    }
}
