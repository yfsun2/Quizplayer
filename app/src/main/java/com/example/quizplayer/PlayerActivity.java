package com.example.quizplayer;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PlayerActivity extends AppCompatActivity {
    private EditText etServerIp, etServerPort, etPlayerName;
    private Button btnConnect, btnAnswer;
    private TextView tvStatus, tvMessage,tvVersion;

    private Socket socket;
    private BufferedReader reader;
    private OutputStream writer;
    private boolean isConnected = false;
    private boolean isQuizActive = false;
    private boolean isProcessingAnswer = false; // 防止重复提交

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // 初始化视图
        etServerIp = findViewById(R.id.etServerIp);
        etServerIp.setText("192.168.10.17");
        etServerPort = findViewById(R.id.etServerPort);
        etPlayerName = findViewById(R.id.etPlayerName);
        btnConnect = findViewById(R.id.btnConnect);
        btnAnswer = findViewById(R.id.btnAnswer);
        // 初始设置选择器
//        btnAnswer.setBackgroundResource(R.drawable.btn_selector);
        tvStatus = findViewById(R.id.tvStatus);
        tvMessage = findViewById(R.id.tvMessage);
        tvVersion=findViewById(R.id.tvVersion);
        etServerPort.setText("12345");

        // 连接/断开按钮点击事件
        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                connectToServer();
            } else {
                disconnectFromServer();
            }
        });

        // 抢答按钮点击事件
        btnAnswer.setOnClickListener(v -> attemptAnswer());
        checkUpdate();
    }

    @SuppressLint("SetTextI18n")
    private void checkUpdate() {
        // 1. 获取本地版本号
        PackageManager pm = getPackageManager();
        int localVersionCode = 0;
        try {
            PackageInfo info = pm.getPackageInfo(getPackageName(), 0);
            handler.post(()->tvVersion.setText("版本号:"+ info.versionCode +" 版本名称:抢答系统选手端v"+info.versionName));
            localVersionCode = info.versionCode;
        } catch (Throwable e) {
            e.printStackTrace();
        }

        // 2. 请求服务器最新版本信息（使用OkHttp示例）
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("https://raw.githubusercontent.com/yfsun2/Quizplayer/master/app/update_info.json")
                .build();
        int finalLocalVersionCode = localVersionCode;
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                handler.post(()-> Toast.makeText(PlayerActivity.this, "请检查网络连接", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();

                    UpdateInfo info = new UpdateInfo();
                    JSONObject jsonObject = null;
                    try {
                        jsonObject = new JSONObject(json);
                        info.latestVersionCode = jsonObject.getInt("latestVersionCode");
                        info.latestVersionName = jsonObject.getString("latestVersionName");
                        info.apkUrl = jsonObject.getString("apkUrl");
                        info.updateDesc = jsonObject.getString("updateDesc");
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }

                    // 3. 对比版本号：服务器版本 > 本地版本 → 需要更新
                    if (info.latestVersionCode > finalLocalVersionCode) {
                        runOnUiThread(() -> showUpdateDialog(info)); // 主线程显示更新弹窗
                    }
                }
            }
        });
    }

    private void showUpdateDialog(UpdateInfo info) {
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + info.latestVersionName)
                .setMessage(info.updateDesc) // 显示更新内容
                .setCancelable(false) // 强制更新时设置为false（不允许取消）
                .setPositiveButton("立即更新", (dialog, which) -> {
                    downloadApk(info.apkUrl); // 开始下载APK
                })
                .setNegativeButton("稍后再说", (dialog, which) -> {
                    // 非强制更新时，允许用户取消
                })
                .show();
    }

    // 使用系统DownloadManager下载（推荐，自动处理后台下载）
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void downloadApk(String apkUrl) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));

        // 配置下载参数
        request.setTitle("抢答系统更新")
                .setDescription("正在下载新版本...")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "quiz_update.apk")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED) // 显示下载通知
                .setMimeType("application/vnd.android.package-archive"); // 指定APK类型

        // 开始下载，获取下载ID（用于监听下载完成）
        long downloadId = manager.enqueue(request);

        // 注册广播监听下载完成
        registerReceiver(new BroadcastReceiver() {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    // 下载完成，获取APK路径
                    Uri apkUri = manager.getUriForDownloadedFile(downloadId);
                    installApk(apkUri); // 调用安装方法
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void installApk(Uri apkUri) {
        // 1. 检查Android 8.0+的安装未知应用权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean hasPermission = getPackageManager().canRequestPackageInstalls();
            if (!hasPermission) {
                // 无权限，引导用户开启
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 100);
                return;
            }
        }

        // 2. 启动安装界面
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // 授予URI读取权限
        startActivity(installIntent);
    }

    // 处理权限请求结果（Android 8.0+）
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().canRequestPackageInstalls()) {
                // 用户已授权，重新执行安装
                // （需重新获取APK的Uri，可通过DownloadManager再次查询）
            }
        }
    }

    // 尝试抢答
    private void attemptAnswer() {
        // 检查状态，防止重复点击
        if (isProcessingAnswer || !isQuizActive || !isConnected) {
            return;
        }

        isProcessingAnswer = true;
        btnAnswer.setEnabled(false); // 立即禁用按钮
        btnAnswer.setText("提交中...");

        new Thread(() -> {
            try {
                // 再次检查连接状态
                if (socket == null || socket.isClosed() || !isConnected) {
                    throw new IOException("连接已断开");
                }

                // 发送抢答请求
                JSONObject message = new JSONObject();
                message.put("type", "answer");
                sendMessage(message.toString());

            } catch (Exception e) {
                handler.post(() -> {
                    Toast.makeText(PlayerActivity.this, "抢答失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    // 只有在抢答仍活跃时才重新启用按钮
                    if (isQuizActive) {
                        updateAnswerButton(true, "抢答！");
                    }
                    isProcessingAnswer = false;
                });
            }
        }).start();
    }

    // 连接服务器
    private void connectToServer() {
        String ip = etServerIp.getText().toString().trim();
        String portStr = etServerPort.getText().toString().trim();
        String name = etPlayerName.getText().toString().trim();

        if (ip.isEmpty() || portStr.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (Throwable e) {
            Toast.makeText(this, "端口格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                if (socket != null) socket.close();

                socket = new Socket(ip, port);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = socket.getOutputStream();

                // 发送身份信息
                writer.write(("player:" + name + ":android\n").getBytes());
                writer.flush();

                isConnected = true;
                handler.post(() -> {
                    tvStatus.setText("状态: 已连接");
                    btnConnect.setText("断开连接");
                    tvMessage.setText("等待抢答开始");
                    etServerIp.setEnabled(false);
                    etServerPort.setEnabled(false);
                    etPlayerName.setEnabled(false);
//                    Toast.makeText(this, "与服务器连接成功", Toast.LENGTH_SHORT).show();
                });

                // 开始接收消息
                receiveMessages();

            } catch (Throwable e) {
                handler.post(() -> {
                    Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    resetConnectionState();
                });
            }
        }).start();
    }

    // 断开连接
    private void disconnectFromServer() {
        new Thread(() -> {
            try {
                if (isConnected && socket != null && !socket.isClosed()) {
                    JSONObject message = new JSONObject();
                    message.put("type", "disconnect");
                    sendMessage(message.toString());

                    socket.close();
                    reader.close();
                    writer.close();
                }
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                isConnected = false;
                isQuizActive = false;
                handler.post(this::resetConnectionState);
            }
        }).start();
    }

    // 重置连接状态
    private void resetConnectionState() {
        tvStatus.setText("状态: 未连接");
        btnConnect.setText("连接服务器");
        etServerIp.setEnabled(true);
        etServerPort.setEnabled(true);
        etPlayerName.setEnabled(true);
        updateAnswerButton(false, "等待抢答开始");
        tvMessage.setText("请先连接服务器");
        isProcessingAnswer = false;
    }

    // 发送消息
    private void sendMessage(String message) {
        new Thread(()->{
            try{
                if (writer != null && !socket.isClosed()) {
                    writer.write((message + "\n").getBytes());
                    writer.flush();
                }
            }catch (Throwable e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 接收消息
//    private void receiveMessages() {
//        new Thread(() -> {
//            String line;
//            try {
//                while (isConnected && (line = reader.readLine()) != null) {
//                    processMessage(line);
//                }
//            } catch (Throwable e) {
//                if (isConnected) {
//                    handler.post(() -> {
//                        Toast.makeText(PlayerActivity.this, "连接已断开", Toast.LENGTH_SHORT).show();
//                        resetConnectionState();
//                    });
//                }
//            }
//        }).start();
//    }

    // 接收消息
    private void receiveMessages() {
        new Thread(() -> {
            String line;
            try {
                // 只要连接有效就一直读
                while (isConnected && (line = reader.readLine()) != null) {
                    processMessage(line);
                }

                // -------------- 关键修复 --------------
                // 读到 null = 服务端主动断开连接
                if (isConnected) {
                    handler.post(() -> {
                        Toast.makeText(PlayerActivity.this, "服务器已断开连接", Toast.LENGTH_SHORT).show();
                        resetConnectionState();
                    });
                }

            } catch (Throwable e) {
                // 异常也视为断开
                if (isConnected) {
                    handler.post(() -> {
                        Toast.makeText(PlayerActivity.this, "连接异常断开", Toast.LENGTH_SHORT).show();
                        resetConnectionState();
                    });
                }
            } finally {
                // 统一标记断开
                isConnected = false;
                isQuizActive = false;
            }
        }).start();
    }

    // 处理消息
    private void processMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");

            switch (type) {
                case "quiz_started":
                    isQuizActive = true;
                    handler.post(() -> {
                        updateAnswerButton(true, "抢答！");
                        try {
                            tvMessage.setText(json.getString("message"));
                        } catch (Throwable e) {
                            throw new RuntimeException(e);
                        }
                        isProcessingAnswer = false;
                    });
                    break;

                case "quiz_ended":
                    isQuizActive = false;
                    handler.post(() -> {
                        updateAnswerButton(false, "等待抢答开始");
                        try {
                            tvMessage.setText(json.getString("message"));
                        } catch (Throwable e) {
                            throw new RuntimeException(e);
                        }
                        isProcessingAnswer = false;
                    });
                    break;

                case "answer_result":
                    isQuizActive = false;
                    boolean success = json.getBoolean("success");
                    String winner = json.getString("winner");
                    boolean isSelf = json.getBoolean("is_self");

                    handler.post(() -> {
                        if (success) {
                            if (isSelf) {
                                tvMessage.setText("恭喜！你抢答成功！");
                            } else {
                                tvMessage.setText(winner + " 抢答成功！");
                            }
                        } else {
                            tvMessage.setText("抢答失败，" + winner + " 已抢先一步！");
                        }
                        btnAnswer.setText("抢答结束");
                        btnAnswer.setBackgroundTintList(ColorStateList.valueOf(Color.GRAY));
                        btnAnswer.setEnabled(false);
                        isProcessingAnswer = false;
                    });
                    break;

                case "current_status":
                    isQuizActive = json.getBoolean("quiz_active");
                    handler.post(() -> {
                        if (isQuizActive) {
                            updateAnswerButton(true, "抢答！");
                        } else {
                            updateAnswerButton(false, "等待抢答开始");
                        }
                    });
                    break;

//                case "error":
//                    handler.post(() -> {
//                        try {
//                            Toast.makeText(PlayerActivity.this, json.getString("message"), Toast.LENGTH_SHORT).show();
//                        } catch (Throwable e) {
//                            throw new RuntimeException(e);
//                        }
//                        // 如果是抢答错误且仍在抢答中，重新启用按钮
//                        if (isQuizActive) {
//                            updateAnswerButton(true, "抢答！");
//                        }
//                        isProcessingAnswer = false;
//                    });
//                    break;
                case "error":
                    handler.post(() -> {
                        try {
                            String msg = json.getString("message");
                            Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();

                            // -------------- 关键修复 --------------
                            // 收到重名/已存在管理员等错误 → 立即断开
                            disconnectFromServer();

                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                        if (isQuizActive) {
                            updateAnswerButton(true, "抢答！");
                        }
                        isProcessingAnswer = false;
                    });
                    break;

                case "heartbeat":
                    // 响应心跳
                    new Thread(() -> {
                        try {
                            JSONObject response = new JSONObject();
                            response.put("type", "heartbeat_ack");
                            sendMessage(response.toString());
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    }).start();
                    break;
            }
        } catch (Throwable e) {
            handler.post(() -> tvMessage.setText("收到无效消息"));
        }
    }

    // 更新抢答按钮状态
    private void updateAnswerButton(boolean enabled, String text) {
        btnAnswer.setText(text);
        btnAnswer.setEnabled(enabled);
//        btnAnswer.setBackgroundResource(enabled ? R.color.green : R.color.gray);
        btnAnswer.setBackgroundTintList(enabled?ColorStateList.valueOf(Color.GREEN):ColorStateList.valueOf(Color.GRAY));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isConnected) {
            disconnectFromServer();
        }
    }
}


// 定义更新信息实体类
class UpdateInfo {
    int latestVersionCode;
    String latestVersionName;
    String apkUrl;
    String updateDesc;
}