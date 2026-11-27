package top.niunaijun.gmstest;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.games.GamesSignInClient;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.PlayGamesSdk;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shows what the Google services in this environment answer to sign-in requests. */
public class MainActivity extends Activity {
    private static final int RC_SIGN_IN = 1;
    private static final String GMS = "com.google.android.gms";
    private static final String VENDING = "com.android.vending";
    /** SHA-1 of the certificate Google signs GMS and Play Store with. */
    private static final String GOOGLE_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788";

    private TextView log;
    private GoogleSignInClient signInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        button(root, "상태 다시 확인", v -> status());
        button(root, "Google 로그인", v -> googleSignIn());
        button(root, "Google 조용한 로그인 (silentSignIn)", v -> googleSilent());
        button(root, "Google 로그아웃 (이 앱만)", v -> googleSignOut());
        button(root, "Play 게임즈 로그인 확인 (isAuthenticated)", v -> gamesCheck());
        button(root, "Play 게임즈 로그인 (signIn)", v -> gamesSignIn());
        button(root, "결과 복사", v -> copy());

        log = new TextView(this);
        log.setTextIsSelectable(true);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(12);
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        signInClient = GoogleSignIn.getClient(this, new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build());
        try {
            PlayGamesSdk.initialize(this);
        } catch (Throwable t) {
            line("PlayGamesSdk.initialize 실패: " + t);
        }
        status();
    }

    private void button(LinearLayout root, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void line(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.append(t + " " + s + "\n");
    }

    private void status() {
        line("== 상태 ==");
        line("Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        line("GMS 사용 가능: " + code + " " + (code == ConnectionResult.SUCCESS ? "SUCCESS"
                : GoogleApiAvailability.getInstance().getErrorString(code)));
        pkg(GMS);
        pkg(VENDING);
        try {
            Account[] accounts = AccountManager.get(this).getAccountsByType("com.google");
            line("AccountManager com.google 계정: " + accounts.length);
            for (Account a : accounts) {
                line("  " + a.name);
            }
        } catch (Throwable t) {
            line("AccountManager 오류: " + t);
        }
        GoogleSignInAccount last = GoogleSignIn.getLastSignedInAccount(this);
        line("이 앱의 마지막 Google 로그인: " + (last == null ? "없음" : last.getEmail()));
    }

    @SuppressWarnings("deprecation")
    private void pkg(String name) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES);
            boolean microG = false;
            try {
                PackageInfo meta = getPackageManager().getPackageInfo(name, PackageManager.GET_META_DATA);
                microG = meta.applicationInfo.metaData != null
                        && meta.applicationInfo.metaData.containsKey("fake-signature");
            } catch (Throwable ignored) {
            }
            String sha = pi.signatures != null && pi.signatures.length > 0 ? sha1(pi.signatures[0]) : "없음";
            line(name + " " + pi.versionName + " (" + pi.versionCode + ")" + (microG ? " [microG]" : ""));
            line("  서명 SHA-1 " + sha + (GOOGLE_SHA1.equals(sha) ? " = Google" : " != Google"));
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi2 = getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] s = pi2.signingInfo == null ? null : pi2.signingInfo.getApkContentsSigners();
                String sha2 = s != null && s.length > 0 ? sha1(s[0]) : "없음";
                line("  signingInfo SHA-1 " + sha2 + (GOOGLE_SHA1.equals(sha2) ? " = Google" : " != Google"));
            }
        } catch (PackageManager.NameNotFoundException e) {
            line(name + ": 설치 안 됨");
        } catch (Throwable t) {
            line(name + " 오류: " + t);
        }
    }

    private static String sha1(Signature s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray());
        StringBuilder b = new StringBuilder();
        for (byte x : d) {
            b.append(String.format("%02x", x));
        }
        return b.toString();
    }

    private void googleSignIn() {
        line("== Google 로그인 ==");
        try {
            startActivityForResult(signInClient.getSignInIntent(), RC_SIGN_IN);
        } catch (Throwable t) {
            line("로그인 화면 열기 실패: " + t);
        }
    }

    private void googleSilent() {
        line("== Google silentSignIn ==");
        signInClient.silentSignIn().addOnCompleteListener(task -> {
            try {
                GoogleSignInAccount a = task.getResult(ApiException.class);
                line("성공: " + a.getEmail() + " / " + a.getDisplayName());
            } catch (ApiException e) {
                line("실패: " + describe(e.getStatusCode()));
            } catch (Throwable t) {
                line("실패: " + t);
            }
        });
    }

    private void googleSignOut() {
        signInClient.signOut().addOnCompleteListener(task ->
                line("이 앱 Google 로그아웃: " + (task.isSuccessful() ? "완료" : String.valueOf(task.getException()))));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_SIGN_IN) {
            return;
        }
        line("로그인 화면 결과 코드: " + resultCode);
        try {
            GoogleSignInAccount a = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            line("성공: " + a.getEmail() + " / " + a.getDisplayName());
        } catch (ApiException e) {
            line("실패: " + describe(e.getStatusCode()));
        } catch (Throwable t) {
            line("실패: " + t);
        }
    }

    private static String describe(int code) {
        String name = GoogleSignInStatusCodes.getStatusCodeString(code);
        String hint = "";
        if (code == 10) {
            hint = " (DEVELOPER_ERROR: GMS가 Google 서버까지 요청은 보냈지만, 이 테스트 앱이 Google Cloud에 등록되지 않아 거절됨. 통신 경로는 정상)";
        } else if (code == 12501) {
            hint = " (사용자가 취소했거나 로그인 화면이 바로 닫힘)";
        } else if (code == 4) {
            hint = " (로그인 필요: 기기에 계정이 없거나 이 앱이 처음 로그인)";
        } else if (code == 7) {
            hint = " (네트워크 오류)";
        } else if (code == 8 || code == 12500) {
            hint = " (GMS 내부 오류)";
        }
        return code + " " + name + hint;
    }

    private void gamesCheck() {
        line("== Play 게임즈 isAuthenticated ==");
        GamesSignInClient c = PlayGames.getGamesSignInClient(this);
        c.isAuthenticated().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                boolean ok = task.getResult().isAuthenticated();
                line("로그인 상태: " + ok);
                if (ok) {
                    player();
                }
            } else {
                gamesError(task.getException());
            }
        });
    }

    private void gamesSignIn() {
        line("== Play 게임즈 signIn ==");
        GamesSignInClient c = PlayGames.getGamesSignInClient(this);
        c.signIn().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                boolean ok = task.getResult().isAuthenticated();
                line("signIn 결과: " + ok);
                if (ok) {
                    player();
                }
            } else {
                gamesError(task.getException());
            }
        });
    }

    private void player() {
        PlayGames.getPlayersClient(this).getCurrentPlayer().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                line("플레이어: " + task.getResult().getDisplayName() + " (" + task.getResult().getPlayerId() + ")");
            } else {
                gamesError(task.getException());
            }
        });
    }

    private void gamesError(Exception e) {
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            line("실패: " + code + " " + e.getMessage());
        } else {
            line("실패: " + e);
        }
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("gms-test", log.getText()));
        Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
    }
}
