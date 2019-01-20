/*
 * Copyright 2017 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Demonstrates how to run an audio recognition model in Android.

This example loads a simple speech recognition model trained by the tutorial at
https://www.tensorflow.org/tutorials/audio_training

The model files should be downloaded automatically from the TensorFlow website,
but if you have a custom model you can update the LABEL_FILENAME and
MODEL_FILENAME constants to point to your own files.

The example application displays a list view with all of the known audio labels,
and highlights each one when it thinks it has detected one through the
microphone. The averaging of results to give a more reliable signal happens in
the RecognizeCommands helper class.
*/

package org.tensorflow.demo;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.support.v4.view.InputDeviceCompat;

import myandroid.view.IWindowManager;

/**
 * An activity that listens for audio and then uses a TensorFlow model to detect particular classes,
 * by default a small set of action words.
 */
public class SpeechActivity extends Activity {


  private static final String LABEL_FILENAME = "file:///android_asset/conv_actions_labels.txt";
  // UI elements.
  private static final int REQUEST_RECORD_AUDIO = 13;
  private Button quitButton;
  private Button upButton;
  private Button downButton;

  private ListView labelsListView;
  private static final String LOG_TAG = SpeechActivity.class.getSimpleName();
  private WebView webView;
  // Working variables.
  private static InputManager im;
  private static Method injectInputEventMethod;
  private static IWindowManager wm;

  private List<String> displayedLabels = new ArrayList<>();

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


  private  void handleClick(int x,int y) {

    try {
      long downTime = SystemClock.uptimeMillis();
      injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 0, downTime, downTime, x, y, 1.0f);

      injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 1, downTime, SystemClock.uptimeMillis(), x,y, 1.0f);


    } catch (Exception e) {
      e.printStackTrace();
    }

  }

    private  void handleMove(float movey) {
      DisplayMetrics dm = new DisplayMetrics();
      getWindowManager().getDefaultDisplay().getMetrics(dm);
    long downTime = SystemClock.uptimeMillis();
    int x=dm.widthPixels/2;
    int y=dm.heightPixels/2;
    try {
      injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 0, downTime, downTime, x, y, 1.0f);
      //injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 1, downTime, SystemClock.uptimeMillis(), x,y, 1.0f);
      injectMotionEvent(im, injectInputEventMethod, InputDeviceCompat.SOURCE_TOUCHSCREEN, 2, downTime, SystemClock.uptimeMillis(), dm.widthPixels/2, dm.heightPixels/2+movey, 1.0f);

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

  Paint paint;
  Canvas topCanvas,leftCanvas;
  private Bitmap topBitmap,leftBitmap;
  FrameLayout mylayout;
  TextView topTextView;
  TextView leftTextView;
  public static List<String> xScales=Arrays.asList("zero","one","two","three");
  public static List<String> yScales=Arrays.asList("four","five","six","seven","eight","nine");
  int m_screenWidth, m_screenHeight;
  public static ArrayList<Integer> xScalesCo=new ArrayList<Integer>();
  public static ArrayList<Integer> yScalesCo=new ArrayList<Integer>();
  private void paintCoodinate()
  {
    paint = new Paint();
    curXindex=curYindex=-1;
    paint.setStrokeWidth(5);//笔宽5像素
    paint.setColor(Color.RED);//设置为红笔
    //	paint.setAntiAlias(false);//锯齿不显示

    DisplayMetrics dm = new DisplayMetrics();

    getWindowManager().getDefaultDisplay().getMetrics(dm);

     m_screenWidth=dm.widthPixels;
     m_screenHeight=dm.heightPixels;




    topBitmap = Bitmap.createBitmap(m_screenWidth, m_screenHeight, Bitmap.Config.ARGB_8888); //设置位图的宽高,bitmap为透明
    topCanvas = new Canvas(topBitmap);
    topCanvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);//设置为透明，画布也是透明
    paint.setTextSize( 20);
      int iindex=0;
      int xwidth=m_screenWidth/(xScales.size()+1);
      for (String s:xScales
           ) {
        topCanvas.drawText(s,(iindex+1)*xwidth+5,10,paint);
        topCanvas.drawLine((iindex+1)*xwidth, 5, (iindex+1)*xwidth, m_screenHeight, paint);
        xScalesCo.add((iindex+1)*xwidth);
          iindex++;
      }

   // leftBitmap = Bitmap.createBitmap(m_screenWidth/20, m_screenHeight, Bitmap.Config.ARGB_8888); //设置位图的宽高,bitmap为透明
    //leftCanvas = new Canvas(leftBitmap);
    //leftCanvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);//设置为透明，画布也是透明

    int ywidth=m_screenHeight/(yScales.size()+1);
      iindex=0;
      for (String s:yScales
              ) {
        topCanvas.drawText(s,0,(iindex+1)*ywidth-5,paint);
        topCanvas.drawLine(0,(iindex+1)*ywidth,  m_screenWidth,(iindex+1)*ywidth, paint);
        yScalesCo.add((iindex+1)*ywidth);
          iindex++;
      }

    /**
    //在画布上贴张小图
    Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);
    canvas.drawBitmap(bm, 0, 0, paint);




    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));//橡皮擦一样擦除
    canvas.drawLine(0, 20, 750, 200, paint);
     **/
   // paint.setXfermode(null);//取消擦除模式

    //paint.setColor(Color.BLUE);
    //canvas.drawLine(0, 20, 750, 200, paint);


    Drawable topDrawable = new BitmapDrawable(getResources(),topBitmap) ;
    topTextView.setBackground(topDrawable);

    //Drawable leftDrawable = new BitmapDrawable(getResources(),leftBitmap) ;
    //leftTextView.setBackground(leftDrawable);


   // FrameLayout.LayoutParams Params = (FrameLayout.LayoutParams)topTextView.getLayoutParams();
   // Params.width = 50;
   // Params.height = 1100;
   // topTextView.setLayoutParams(Params);



  }

  private int dip2px(Context context, float dipValue)
  {
    Resources r = context.getResources();
    return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dipValue, r.getDisplayMetrics());
  }

  private MsgReceiver msgReceiver;
  private Intent mIntent;
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    // Set up the UI.
    super.onCreate(savedInstanceState);

    //动态注册广播接收器
    msgReceiver = new MsgReceiver();
    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction("org.tensorflow.demo.RECEIVER");
    registerReceiver(msgReceiver, intentFilter);



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
      setContentView(R.layout.activity_speech);
    mylayout = (FrameLayout)findViewById(R.id.myLayout);
    topTextView =(TextView)findViewById(R.id.topTextView);
    topTextView.setVisibility(View.INVISIBLE);
   // leftTextView =(TextView)findViewById(R.id.leftTextView);
    quitButton = (Button) findViewById(R.id.quit);
    upButton = (Button) findViewById(R.id.up);
    downButton = (Button) findViewById(R.id.down);
    quitButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            moveTaskToBack(true);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
          }
        });

    upButton.setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                Toast.makeText(getApplicationContext(), "up clicked", Toast.LENGTH_LONG).show();
                handleMove(40);
              }
            });

    downButton.setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                Toast.makeText(getApplicationContext(), "down clicked", Toast.LENGTH_LONG).show();
                handleMove(-40);
              }
            });




    webView = (WebView) findViewById(R.id.webview);


    webView.setWebViewClient(new WebViewClient());
    WebSettings webSettings=webView.getSettings();
    webSettings.setJavaScriptEnabled(true);
    webSettings.setDomStorageEnabled(true);
    webView.loadUrl("http://www.sina.com.cn");

    paintCoodinate();
    // Start the recording and recognition threads.
    requestMicrophonePermission();
    //startService(new Intent(getBaseContext(), MyService.class));

  }

  @Override
  protected void onDestroy() {
    //停止服务
    stopService(mIntent);
    //注销广播
    unregisterReceiver(msgReceiver);
    super.onDestroy();
  }

  private void requestMicrophonePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(
          new String[]{android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_RECORD_AUDIO);
    }
  }

  private boolean isServiceRunning() {
    ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
    for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)){
      if("org.tensorflow.demo.MyService".equals(service.service.getClassName())) {
        return true;
      }
    }
    return false;
  }
  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == REQUEST_RECORD_AUDIO
        && grantResults.length > 0
        && grantResults[0] == PackageManager.PERMISSION_GRANTED
            && !isServiceRunning()) {
      startService(new Intent(getBaseContext(), MyService.class));
    }
  }

private int curYindex=-1,curXindex=-1;//已选择坐标线索引
  private String prevCommand="";
  public static int ifstop=0;
  private Timer timer;
  private TimerTask task;
  int downspeed=40,upspeed=40;

  /**
   * 广播接收器
   *
   *
   */
  public class MsgReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      //拿到进度，更新UI
      String scommand = intent.getStringExtra("command");

      if(yScales.contains(scommand) || xScales.contains(scommand)) {//yes
        /**
        if(curYScal!=0 || curXScal!=0) {
          if (topTextView.getVisibility() == View.VISIBLE) {
            topTextView.setVisibility(View.INVISIBLE);
          } else {
            topTextView.setVisibility(View.VISIBLE);
          }
        }
         **/
        if(yScales.contains(scommand)){
          int nindex=yScales.indexOf(scommand);
          if(nindex!=curYindex && curYindex!=-1)
          {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));//橡皮擦一样擦除

            topCanvas.drawLine(0, yScalesCo.get(curYindex), m_screenWidth, yScalesCo.get(curYindex), paint);
            paint.setXfermode(null);//取消擦除模式
            paint.setColor(Color.RED);
            topCanvas.drawLine(0, yScalesCo.get(curYindex), m_screenWidth, yScalesCo.get(curYindex), paint);
          }
          paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));//橡皮擦一样擦除

          topCanvas.drawLine(0, yScalesCo.get(nindex), m_screenWidth, yScalesCo.get(nindex), paint);
          paint.setXfermode(null);//取消擦除模式
          paint.setColor(Color.BLUE);
          topCanvas.drawLine(0, yScalesCo.get(nindex), m_screenWidth, yScalesCo.get(nindex), paint);
          Drawable topDrawable = new BitmapDrawable(getResources(),topBitmap) ;
          topTextView.setBackground(topDrawable);
          curYindex=nindex;
        }

        if(xScales.contains(scommand)){
          int nindex=xScales.indexOf(scommand);
          if(nindex!=curXindex && curXindex!=-1)
          {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));//橡皮擦一样擦除

            topCanvas.drawLine(xScalesCo.get(curXindex),0 , xScalesCo.get(curXindex),m_screenHeight , paint);
            paint.setXfermode(null);//取消擦除模式
            paint.setColor(Color.RED);
            topCanvas.drawLine(xScalesCo.get(curXindex),0 , xScalesCo.get(curXindex),m_screenHeight , paint);
          }
          paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));//橡皮擦一样擦除

          topCanvas.drawLine(xScalesCo.get(nindex), 0, xScalesCo.get(nindex), m_screenHeight, paint);
          paint.setXfermode(null);//取消擦除模式
          paint.setColor(Color.BLUE);
          topCanvas.drawLine(xScalesCo.get(nindex), 0, xScalesCo.get(nindex), m_screenHeight, paint);
          Drawable topDrawable = new BitmapDrawable(getResources(),topBitmap) ;
          topTextView.setBackground(topDrawable);
          curXindex=nindex;
        }

      }else
      {
        if(scommand.equals("stop") && task!=null && timer!=null)
        {
          task.cancel();
          timer.cancel();
          task=null;
          timer=null;

          downspeed=upspeed=40;
        }
        if(scommand.equals("up"))
        {
          if(prevCommand.equals("up") )
          {
            upspeed+=40;
            if(timer==null && task==null)
            {
              timer = new Timer();
              task = new TimerTask() {
                @Override
                public void run() {
                  runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                      webView.scrollBy(0,-upspeed);

                    }
                  });
                }
              };
              timer.scheduleAtFixedRate(task, 0, 1000);//delayed auto scroll time

            }

          }else
          {
            webView.scrollBy(0,-upspeed);
          }
        }
        if(scommand.equals("down"))
        {


          if(prevCommand.equals("down"))
          {
            downspeed+=40;
            if(timer==null && task==null)
            {
              timer = new Timer();
              task = new TimerTask() {
                @Override
                public void run() {
                  runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                      webView.scrollBy(0,downspeed);

                    }
                  });
                }
              };
              timer.scheduleAtFixedRate(task, 0, 1000);//delayed auto scroll time
            }


          }else
          {
            webView.scrollBy(0,downspeed);
          }

        }
        if(scommand.equals("yes") )
        {

          if (topTextView.getVisibility() == View.VISIBLE) {
            topTextView.setVisibility(View.INVISIBLE);
          } else {
            paintCoodinate();
            topTextView.setVisibility(View.VISIBLE);

          }
        }

        if(scommand.equals("go"))
        {
          handleClick(xScalesCo.get(curXindex),yScalesCo.get(curYindex));

        }
        if(scommand.equals("backward"))
        {
          webView.goBack();
        }
        if(scommand.equals("forward"))
        {
          webView.goForward();
        }

      }
      prevCommand=scommand;
    }

  }


}
