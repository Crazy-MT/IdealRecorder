package tech.oom.idealrecorder.record;

import android.media.AudioFormat;
import android.media.AudioRecord;

import tech.oom.idealrecorder.IdealConst;
import tech.oom.idealrecorder.IdealRecorder;
import tech.oom.idealrecorder.utils.Log;


public class Recorder {
    public static int TIMER_INTERVAL = 100;
    private static final String TAG = "Recorder";
    private IdealRecorder.RecordConfig recordConfig;
    private AudioRecord mAudioRecorder = null;
    private RecorderCallback mCallback;
    private int bufferSize;
    private int recBufferBytePtr;
    private int recBufferByteSize;
    int sampleBytes;
    int frameByteSize;
    private boolean isRecord = false;
    private Thread mThread = null;
    private byte[] recBuffer;
    private Runnable RecordRun = new Runnable() {

        public void run() {
            if ((mAudioRecorder != null) && (mAudioRecorder.getState() == 1)) {

                try {
                    mAudioRecorder.stop();
                    mAudioRecorder.startRecording();
                } catch (Exception e) {
                    e.printStackTrace();
                    recordFailed(IdealConst.RecorderErrorCode.RECORDER_EXCEPTION_OCCUR);
                    mAudioRecorder = null;
                }
            }
            if ((mAudioRecorder != null) &&
                    (mAudioRecorder.getState() == 1) && (mAudioRecorder.getRecordingState() == 1)) {
                Log.e(TAG, "no recorder permission or recorder is not available right now");
                recordFailed(IdealConst.RecorderErrorCode.RECORDER_PERMISSION_ERROR);
                mAudioRecorder = null;
            }
            for (int i = 0; i < 2; i++) {
                if (mAudioRecorder == null) {
                    isRecord = false;
                    break;
                }
                mAudioRecorder.read(recBuffer, 0, recBuffer.length);
            }
            while (isRecord) {
                int reallySampledBytes = 0;
                try {
                    reallySampledBytes = mAudioRecorder.read(recBuffer, 0, recBuffer.length);

                    int i = 0;
                    while ( i < reallySampledBytes ) {
                        float sample = (float)( recBuffer[recBufferBytePtr+i  ] & 0xFF
                                | recBuffer[recBufferBytePtr+i+1] << 8 );

                        // THIS is the point were the work is done:
                        // Increase level by about 6dB:
                        sample *= 2;
                        // Or increase level by 20dB:
                        // sample *= 10;
                        // Or if you prefer any dB value, then calculate the gain factor outside the loop
                        // float gainFactor = (float)Math.pow( 10., dB / 20. );    // dB to gain factor
                        // sample *= gainFactor;

                        // Avoid 16-bit-integer overflow when writing back the manipulated data:
                        if ( sample >= 32767f ) {
                            recBuffer[recBufferBytePtr+i  ] = (byte)0xFF;
                            recBuffer[recBufferBytePtr+i+1] =       0x7F;
                        } else if ( sample <= -32768f ) {
                            recBuffer[recBufferBytePtr+i  ] =       0x00;
                            recBuffer[recBufferBytePtr+i+1] = (byte)0x80;
                        } else {
                            int s = (int)( 0.5f + sample );  // Here, dithering would be more appropriate
                            recBuffer[recBufferBytePtr+i  ] = (byte)(s & 0xFF);
                            recBuffer[recBufferBytePtr+i+1] = (byte)(s >> 8 & 0xFF);
                        }
                        i += 2;
                    }

                    // Do other stuff like saving the part of buffer to a file
                    // if ( reallySampledBytes > 0 ) { ... save recBuffer+recBufferBytePtr, length: reallySampledBytes

                    // Then move the recording pointer to the next position in the recording buffer
                    recBufferBytePtr += reallySampledBytes;

                    // Wrap around at the end of the recording buffer, e.g. like so:
                    if ( recBufferBytePtr >= recBufferByteSize ) {
                        recBufferBytePtr = 0;
                        sampleBytes = frameByteSize;
                    } else {
                        sampleBytes = recBufferByteSize - recBufferBytePtr;
                        if ( sampleBytes > frameByteSize )
                            sampleBytes = frameByteSize;
                    }

                } catch (Exception e) {
                    isRecord = false;
                    recordFailed(IdealConst.RecorderErrorCode.RECORDER_EXCEPTION_OCCUR);
                }
                if (reallySampledBytes == recBuffer.length) {
                    mCallback.onRecorded(recBuffer);
                } else {
                    recordFailed(IdealConst.RecorderErrorCode.RECORDER_READ_ERROR);
                    isRecord = false;
                }
            }
            Log.i(TAG, "out of the reading while loop,i'm going to stop");
            unInitializeRecord();
            doRecordStop();
        }
    };


    public Recorder(IdealRecorder.RecordConfig config, RecorderCallback callback) {
        this.mCallback = callback;
        this.recordConfig = config;
    }

    public void setRecordConfig(IdealRecorder.RecordConfig config) {
        this.recordConfig = config;
    }


    public boolean start() {
        isRecord = true;
        synchronized (this) {
            if (doRecordReady()) {
                Log.d(TAG, "doRecordReady");
                if (initializeRecord()) {
                    Log.d(TAG, "initializeRecord");
                    if (doRecordStart()) {
                        Log.d(TAG, "doRecordStart");

                        mThread = new Thread(RecordRun);
                        mThread.start();
                        return true;
                    }
                }
            }
        }
        isRecord = false;
        return false;
    }


    public void stop() {
        synchronized (this) {
            mThread = null;
            isRecord = false;
        }
    }

    public void immediateStop() {
        isRecord = false;
        if (mThread != null) {
            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mThread = null;
    }

    public boolean isStarted() {
        return isRecord;
    }

    private boolean initializeRecord() {
        synchronized (this) {
            try {
                if (mCallback == null) {
                    Log.e(TAG, "Error VoiceRecorderCallback is  null");
                    return false;
                }
                if (recordConfig == null) {
                    Log.e(TAG, "Error recordConfig is null");
                    return false;
                }
                short nChannels;
                int sampleRate;
                short bSamples;
                int audioSource;
                int audioFormat;
                int channelConfig;
                if (recordConfig.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) {
                    bSamples = 16;
                } else {
                    bSamples = 8;
                }

                if ((channelConfig = recordConfig.getChannelConfig()) == AudioFormat.CHANNEL_IN_MONO) {
                    nChannels = 1;
                } else {
                    nChannels = 2;
                }
                audioSource = recordConfig.getAudioSource();
                sampleRate = recordConfig.getSampleRate();
                audioFormat = recordConfig.getAudioFormat();
                int framePeriod = sampleRate * TIMER_INTERVAL / 1000;
                bufferSize = framePeriod * 2 * bSamples * nChannels / 8;

                recBuffer = new byte[framePeriod * bSamples / 8 * nChannels / 2];
                Log.d(TAG, "buffersize = " + bufferSize);
                int minRecBufBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

                if (bufferSize < minRecBufBytes) {
                    bufferSize = minRecBufBytes;

                    Log.d(TAG, "Increasing buffer size to " + Integer.toString(bufferSize));
                }
                if (mAudioRecorder != null) {
                    unInitializeRecord();
                }
                mAudioRecorder = new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize);

                // Setup the recording buffer, size, and pointer (in this case quadruple buffering)
                recBufferByteSize = minRecBufBytes*2;
                recBuffer = new byte[recBufferByteSize];

                frameByteSize = minRecBufBytes/2;
                sampleBytes = frameByteSize;
                recBufferBytePtr = 0;

                TIMER_INTERVAL = (int) (recBufferByteSize  * 8 / (float)nChannels / (float)bSamples / (float)sampleRate * 1000);

                if (mAudioRecorder.getState() != 1) {
                    mAudioRecorder = null;
                    recordFailed(IdealConst.RecorderErrorCode.RECORDER_PERMISSION_ERROR);
                    Log.e(TAG, "AudioRecord initialization failed,because of no RECORD permission or unavailable AudioRecord ");
                    throw new Exception("AudioRecord initialization failed");
                }
                Log.i(TAG, "initialize  Record");
                return true;
            } catch (Throwable e) {
                if (e.getMessage() != null) {
                    Log.e(TAG, getClass().getName() + e.getMessage());
                } else {
                    Log.e(TAG, getClass().getName() + "Unknown error occured while initializing recording");
                }
                return false;
            }
        }
    }

    private void unInitializeRecord() {
        Log.i(TAG, "unInitializeRecord");
        synchronized (this) {
            if (mAudioRecorder != null) {
                try {
                    mAudioRecorder.stop();
                    mAudioRecorder.release();
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e(TAG, "mAudioRecorder release error!");
                }
                mAudioRecorder = null;
            }
        }
    }

    private boolean doRecordStart() {
        if (mCallback != null) {
            return mCallback.onRecorderStart();
        }
        return true;
    }

    private boolean doRecordReady() {
        if (mCallback != null) {
            return mCallback.onRecorderReady();
        }
        return true;
    }

    private void doRecordStop() {
        if (mCallback != null) {
            mCallback.onRecorderStop();
        }
    }

    private void recordFailed(int errorCode) {
        if (mCallback != null) {
            mCallback.onRecordedFail(errorCode);
        }
    }
}
