package org.tensorflow.demo;


import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.app.Service;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.view.InputDeviceCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import myandroid.view.IWindowManager;

public class MyService extends Service {
    private List<String> labels = new ArrayList<String>();
    // Constants that control the behavior of the recognition code and model
    // settings. See the audio recognition tutorial for a detailed explanation of
    // all these, but you should customize them to match your training settings if
    // you are running your own model.
    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLE_DURATION_MS = 1000;
    private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
    private static final long AVERAGE_WINDOW_DURATION_MS = 500;
    private static final float DETECTION_THRESHOLD = 0.70f;
    private static final int SUPPRESSION_MS = 1500;
    private static final int MINIMUM_COUNT = 3;
    private static final long MINIMUM_TIME_BETWEEN_SAMPLES_MS = 30;

    private static final String MODEL_FILENAME = "file:///android_asset/conv_actions_frozen.pb";
    private static final String INPUT_DATA_NAME = "decoded_sample_data:0";
    private static final String SAMPLE_RATE_NAME = "decoded_sample_data:1";
    private static final String OUTPUT_SCORES_NAME = "labels_softmax";

    short[] recordingBuffer = new short[RECORDING_LENGTH];
    int recordingOffset = 0;
    boolean shouldContinue = true;
    private Thread recordingThread;
    boolean shouldContinueRecognition = true;
    private Thread recognitionThread;
    private final ReentrantLock recordingBufferLock = new ReentrantLock();
    private TensorFlowInferenceInterface inferenceInterface;
    private static final String LOG_TAG = MyService.class.getSimpleName();

    private static final String LABEL_FILENAME = "file:///android_asset/conv_actions_labels.txt";
    private RecognizeCommands recognizeCommands = null;

    //send mouse event
    private static InputManager im;
    private static Method injectInputEventMethod;
    private static IWindowManager wm;

    private static void injectMotionEvent(InputManager im, Method injectInputEventMethod, int inputSource, int action, long downTime, long eventTime, float x, float y, float pressure) throws InvocationTargetException, IllegalAccessException {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, pressure, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        event.setSource(inputSource);
        injectInputEventMethod.invoke(im, new Object[]{event, Integer.valueOf(0)});
    }

    private static void init() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {

        Method getServiceMethod = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", new Class[]{String.class});
        wm = IWindowManager.Stub.asInterface((IBinder) getServiceMethod.invoke(null, new Object[]{"window"}));

        im = (InputManager) InputManager.class.getDeclaredMethod("getInstance", new Class[0]).invoke(null, new Object[0]);
        MotionEvent.class.getDeclaredMethod("obtain", new Class[0]).setAccessible(true);
        injectInputEventMethod = InputManager.class.getMethod("injectInputEvent", new Class[]{InputEvent.class, Integer.TYPE});

    }


    public static  void hanlerDown(float movey) {

        long downTime = SystemClock.uptimeMillis();
        int x=400;
        int y=700;
        try {
            injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 0, downTime, downTime, x, y, 1.0f);
            //injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 1, downTime, SystemClock.uptimeMillis(), x,y, 1.0f);
            injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 2, downTime, SystemClock.uptimeMillis(), x, y+movey, 1.0f);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void injectKeyEvent(InputManager im, Method injectInputEventMethod, KeyEvent event) throws InvocationTargetException, IllegalAccessException {
        injectInputEventMethod.invoke(im, new Object[]{event, Integer.valueOf(0)});
    }

    private static void sendKeyEvent(InputManager im, Method injectInputEventMethod, int inputSource, int keyCode, boolean shift) throws InvocationTargetException, IllegalAccessException {
        long now = SystemClock.uptimeMillis();
        int meta = shift ? 1 : 0;
        injectKeyEvent(im, injectInputEventMethod, new KeyEvent(now, now, 0, keyCode, 0, meta, -1, 0, 0, inputSource));
        injectKeyEvent(im, injectInputEventMethod, new KeyEvent(now, now, 1, keyCode, 0, meta, -1, 0, 0, inputSource));
    }

    private static void pressHome() throws InvocationTargetException, IllegalAccessException {
        sendKeyEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_KEYBOARD, 3, false);
    }
    //send mouse event end

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Let it continue running until it is stopped.
        Toast.makeText(this, "服务已经启动", Toast.LENGTH_LONG).show();
        try {
            init();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        Log.v(LOG_TAG, "Server started");
        // Load the labels for the model, but only display those that don't start
        // with an underscore.
        String actualFilename = LABEL_FILENAME.split("file:///android_asset/")[1];
        Log.i(LOG_TAG, "Reading labels from: " + actualFilename);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(getAssets().open(actualFilename)));
            String line;
            while ((line = br.readLine()) != null) {
                labels.add(line);

            }
            br.close();
        } catch (IOException e) {
            throw new RuntimeException("Problem reading label file!", e);
        }

        // Load the TensorFlow model.
        inferenceInterface = new TensorFlowInferenceInterface(getAssets(), MODEL_FILENAME);
        // Set up an object to smooth recognition results to increase accuracy.
        recognizeCommands =
                new RecognizeCommands(
                        labels,
                        AVERAGE_WINDOW_DURATION_MS,
                        DETECTION_THRESHOLD,
                        SUPPRESSION_MS,
                        MINIMUM_COUNT,
                        MINIMUM_TIME_BETWEEN_SAMPLES_MS);

        startRecording();
        startRecognition();;
        return START_STICKY;


    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(LOG_TAG, "Server stopped");
       // Toast.makeText(this, "服务已经停止", Toast.LENGTH_LONG).show();
    }

    private void record() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // Estimate the buffer size we'll need for this device.
        int bufferSize =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }
        short[] audioBuffer = new short[bufferSize / 2];

        AudioRecord record =
                new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        Log.v(LOG_TAG, "Start recording");

        // Loop, gathering audio data and copying it to a round-robin buffer.
        while (shouldContinue) {
            int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
            int maxLength = recordingBuffer.length;
            int newRecordingOffset = recordingOffset + numberRead;
            int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
            int firstCopyLength = numberRead - secondCopyLength;
            // We store off all the data for the recognition thread to access. The ML
            // thread will copy out of this buffer into its own, while holding the
            // lock, so this should be thread safe.
            recordingBufferLock.lock();
            try {
                System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength);
                System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength);
                recordingOffset = newRecordingOffset % maxLength;
            } finally {
                recordingBufferLock.unlock();
            }
        }

        record.stop();
        record.release();
    }

    public synchronized void startRecognition() {
        if (recognitionThread != null) {
            return;
        }
        shouldContinueRecognition = true;
        recognitionThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                recognize();
                            }
                        });
        recognitionThread.start();
    }

    public synchronized void stopRecognition() {
        if (recognitionThread == null) {
            return;
        }
        shouldContinueRecognition = false;
        recognitionThread = null;
    }
    private Intent intent = new Intent("org.tensorflow.demo.RECEIVER");
    private String prevCommand="";
    private void recognize() {
        Log.v(LOG_TAG, "Start recognition");

        short[] inputBuffer = new short[RECORDING_LENGTH];
        float[] floatInputBuffer = new float[RECORDING_LENGTH];
        float[] outputScores = new float[labels.size()];
        String[] outputScoresNames = new String[] {OUTPUT_SCORES_NAME};
        int[] sampleRateList = new int[] {SAMPLE_RATE};

        int num=0;
        BufferedWriter out = null;
        // Loop, grabbing recorded data and running the recognition model on it.
        while (shouldContinueRecognition) {
            // The recording thread places data in this round-robin buffer, so lock to
            // make sure there's no writing happening and then copy it to our own
            // local version.
            recordingBufferLock.lock();
            try {
                int maxLength = recordingBuffer.length;
                int firstCopyLength = maxLength - recordingOffset;
                int secondCopyLength = recordingOffset;
                System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength);
                System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength);
            } finally {
                recordingBufferLock.unlock();
            }

            // We need to feed in float values between -1.0f and 1.0f, so divide the
            // signed 16-bit inputs.
            for (int i = 0; i < RECORDING_LENGTH; ++i) {
                floatInputBuffer[i] = inputBuffer[i] / 32767.0f;
            }

            // Run the model.
            inferenceInterface.feed(SAMPLE_RATE_NAME, sampleRateList);
            inferenceInterface.feed(INPUT_DATA_NAME, floatInputBuffer, RECORDING_LENGTH, 1);
            inferenceInterface.run(outputScoresNames);
            inferenceInterface.fetch(OUTPUT_SCORES_NAME, outputScores);

            // Use the smoother to figure out if we've had a real recognition event.
            long currentTime = System.currentTimeMillis();
            final RecognizeCommands.RecognitionResult result =
                    recognizeCommands.processLatestResults(outputScores, currentTime);
            if(!result.foundCommand.equals("_silence_")  && result.isNewCommand) {
                Log.v("RecognizeResult:", result.foundCommand);
                FileWriter writer = null;
                try {
                    // 打开一个写文件器，构造函数中的第二个参数true表示以追加形式写文件
                    writer = new FileWriter("/sdcard/download/log.txt", true);
                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");// HH:mm:ss
//获取当前时间
                    Date date = new Date(System.currentTimeMillis());


                    /*
                    if(result.foundCommand.equals("up"))
                    {
                        hanlerDown(40);

                    }
                    if(result.foundCommand.equals("down"))
                    {
                        hanlerDown(-40);
                    }
*/
                    intent.putExtra("command", result.foundCommand);
                    sendBroadcast(intent);
                    /*
                    if(SpeechActivity.yScales.contains(result.foundCommand)
                            ||
                            SpeechActivity.xScales.contains(result.foundCommand)) {

                    }*/


                    writer.write(simpleDateFormat.format(date)+"    "+result.foundCommand+"\r\n");
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if(writer != null){
                            writer.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                prevCommand=result.foundCommand;
            }

        }

        Log.v(LOG_TAG, "End recognition");
    }


    public synchronized void startRecording() {
        if (recordingThread != null) {
            return;
        }
        shouldContinue = true;
        recordingThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                record();
                            }
                        });
        recordingThread.start();
    }

    public synchronized void stopRecording() {
        if (recordingThread == null) {
            return;
        }
        shouldContinue = false;
        recordingThread = null;
        stopService(new Intent(getBaseContext(), MyService.class));
    }


}
