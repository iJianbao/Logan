package com.meituan.flutter_logan;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.dianping.logan.Logan;
import com.dianping.logan.LoganConfig;
import com.dianping.logan.Util;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** FlutterLoganPlugin11 */
public class FlutterLoganPlugin implements FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private static Executor sExecutor;
  private static Executor sMainExecutor = new Executor() {
    private Handler mMainHandler = new Handler();

    @Override
    public void execute(Runnable runnable) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        runnable.run();
      } else {
        mMainHandler.post(runnable);
      }
    }
  };

  private Context mContext;
  private String mLoganFilePath;
  private MethodChannel channel;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    if (mContext == null) {
      mContext = flutterPluginBinding.getApplicationContext();
    }
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_logan");
    channel.setMethodCallHandler(this);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
  }

  /**
   * @param result the reply methods of result MUST be invoked on main thread
   */
  @Override
  public void onMethodCall(MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "init":
        loganInit(call.arguments, result);
        break;
      case "log":
        log(call.arguments, result);
        break;
      case "flush":
        flush(result);
        break;
      case "getUploadPath":
        getUploadPath(call.arguments, result);
        break;
      case "upload":
        uploadToServer(call.arguments, result);
        break;
      case "cleanAllLogs":
        cleanAllLog(result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private void replyOnMainThread(final Result result, final Object r) {
    sMainExecutor.execute(new Runnable() {
      @Override
      public void run() {
        result.success(r);
      }
    });
  }

  private void loganInit(Object args, Result result) {
    final File file = mContext.getExternalFilesDir(null);
    if (file == null) {
      result.success(false);
      return;
    }
    final LoganConfig.Builder builder = new LoganConfig.Builder();
    String encryptKey = "";
    String encryptIV = "";
    if (args instanceof Map) {
      Long maxFileLen = Utils.getLong((Map) args, "maxFileLen");
      if (maxFileLen != null) {
        builder.setMaxFile(maxFileLen);
      }
      final String aesKey = Utils.getString((Map) args, "aesKey");
      if (Utils.isNotEmpty(aesKey)) {
        encryptKey = aesKey;
      }
      final String aesIv = Utils.getString((Map) args, "aesIv");
      if (Utils.isNotEmpty(aesIv)) {
        encryptIV = aesIv;
      }
    }
    // key iv check.
    if (Utils.isEmpty(encryptKey) || Utils.isEmpty(encryptIV)) {
      result.success(false);
      return;
    }
    mLoganFilePath = file.getAbsolutePath() + File.separator + "logan_v1";
    builder.setCachePath(mContext.getFilesDir().getAbsolutePath())
            .setPath(mLoganFilePath)
            .setEncryptKey16(encryptKey.getBytes())
            .setEncryptIV16(encryptIV.getBytes());
    Logan.init(builder.build());
    result.success(true);
  }

  private void log(Object args, Result result) {
    if (args instanceof Map) {
      String log = Utils.getString((Map) args, "log");
      Integer type = Utils.getInt((Map) args, "type");
      if (Utils.isNotEmpty(log) && type != null) {
        Logan.w(log, type);
      }
    }
    result.success(null);
  }

  private void checkAndInitExecutor() {
    if (sExecutor == null) {
      synchronized (this) {
        if (sExecutor == null) {
          sExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
              return new Thread(r, "flutter-plugin-thread");
            }
          });
        }
      }
    }
  }

  private void flush(Result result) {
    Logan.f();
    result.success(null);
  }

  private void getUploadPath(final Object args, final Result result) {
    if (!(args instanceof Map)) {
      result.success("");
      return;
    }
    if (Utils.isEmpty(mLoganFilePath)) {
      result.success("");
      return;
    }
    checkAndInitExecutor();
    sExecutor.execute(new Runnable() {
      @Override
      public void run() {
        File dir = new File(mLoganFilePath);
        if (!dir.exists()) {
          replyOnMainThread(result, "");
          return;
        }
        final String date = Utils.getString((Map) args, "date");
        File[] files = dir.listFiles();
        if (files == null) {
          replyOnMainThread(result, "");
          return;
        }
        for (File file : files) {
          try {
            String fileDate = Util.getDateStr(Long.parseLong(file.getName()));
            if (date != null && date.equals(fileDate)) {
              replyOnMainThread(result, file.getAbsolutePath());
              return;
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
        replyOnMainThread(result, "");
      }
    });
  }

  private void cleanAllLog(final Result result) {
    if (Utils.isEmpty(mLoganFilePath)) {
      result.success(null);
      return;
    }
    checkAndInitExecutor();
    sExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          File dir = new File(mLoganFilePath);
          Utils.deleteRecursive(dir, false);
        } catch (Exception ignored) {
        } finally {
          replyOnMainThread(result, null);
        }
      }
    });
  }

  private void uploadToServer(final Object args, final Result result) {
    if (!(args instanceof Map)) {
      result.success(false);
      return;
    }
    final String date = Utils.getString((Map) args, "date");
    final String serverUrl = Utils.getString((Map) args, "serverUrl");
    if (Utils.isEmpty(date) || Utils.isEmpty(serverUrl)) {
      result.success(false);
      return;
    }
    final String appId = Utils.getString((Map) args, "appId");
    final String unionId = Utils.getString((Map) args, "unionId");
    final String deviceId = Utils.getString((Map) args, "deviceId");
    final String buildVersion = "30.0.0";
    String versionName = "unKnow";
    int versionCode = 0;
    try {
      PackageManager packageManager = mContext.getPackageManager();
      PackageInfo packageInfo = packageManager.getPackageInfo(
              mContext.getPackageName(), 0);
      versionName = packageInfo.versionName;
      versionCode = packageInfo.versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
    }
    Log.d("logan上传", args.toString());
    Logan.s(serverUrl, date, appId, unionId, deviceId, buildVersion, "" + versionCode, (statusCode, data) -> {
      final String resultData = data != null ? new String(data) : "";
      Log.d("1", "日志上传结果, http状态码: " + statusCode + ", 详细: " + resultData);
      new Handler(Looper.getMainLooper()).post(() -> {
        result.success(statusCode == 200);
      });
    });
    /*
    final RealSendLogRunnable sendLogRunnable = new RealSendLogRunnable() {
      @Override
      protected void onSuccess(boolean success) {
        replyOnMainThread(result, success);
      }
    };
    Map<String, String> params = Utils.getStringMap((Map) args, "params");
    if (params != null) {
      Set<Map.Entry<String, String>> entrySet = params.entrySet();
      for (Map.Entry<String, String> tempEntry : entrySet) {
        sendLogRunnable.addHeader(tempEntry.getKey(), tempEntry.getValue());
      }
    }
    sendLogRunnable.setUrl(serverUrl);
    Logan.s(new String[]{date}, sendLogRunnable);
    */
  }
}
