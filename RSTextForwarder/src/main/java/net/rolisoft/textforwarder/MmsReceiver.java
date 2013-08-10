package net.rolisoft.textforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent)
    {
        final SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || !sp.getBoolean("forward_mms", true) || !MainActivity.isConnectedToInternet(context)) {
            return;
        }

        WakeLocker.acquire(context, 60000);

        try {
            List<TextMessage> messages = getMessagesFrom(context, intent);

            for (TextMessage msg : messages) {
                AsyncTask<TextMessage, Void, Void> asyncTask = new AsyncTask<TextMessage, Void, Void>() {

                    @Override
                    protected Void doInBackground(TextMessage... msgs) {
                        try {
                            JSONObject json = MainActivity.sendRequest("send", new ArrayList<NameValuePair>(Arrays.asList(
                                    new BasicNameValuePair("gacc", sp.getString("g_acc", null)),
                                    new BasicNameValuePair("from", msgs[0].from),
                                    new BasicNameValuePair("body", msgs[0].body)
                            )));
                        } catch (ServerError ex) {
                            MainActivity.displayNotification(context, "Forwarding failed", "Server error: " + ex.toString());
                        } catch (Exception ex) {
                            MainActivity.displayNotification(context, "Forwarding failed", "Send error: " + ex.toString());
                        }

                        return null;
                    }

                };
                asyncTask.execute(msg);
            }
        } catch (Exception ex) {
            MainActivity.displayNotification(context, "Forwarding failed", "Local error: " + ex.toString());
        } finally {
            WakeLocker.release();
        }
    }

    public static List<TextMessage> getMessagesFrom(Context context, Intent intent)
    {
        Bundle bundle = intent.getExtras();
        List<TextMessage> messages = new ArrayList<TextMessage>();

        if (bundle == null || !bundle.containsKey("data")) {
            return messages;
        }

        byte[] data = bundle.getByteArray("data");

        if (data == null || data.length == 0) {
            return messages;
        }

        try {
            String buffer = new String(bundle.getByteArray("data"));

            for (int i = 0; i < 10; i++) {
                try { Thread.sleep(2000); } catch (Exception ex) { }

                Cursor cur = context.getContentResolver().query(Uri.parse("content://mms/inbox"), null, "m_type in (132,128)", null, "date DESC");
                if (cur == null) {
                    continue;
                }

                try {
                    if (cur.getCount() == 0) {
                        continue;
                    }

                    cur.moveToFirst();
                    int cnt = 0;

                    do {
                        int id = cur.getInt(cur.getColumnIndex("_id"));
                        String mid = cur.getString(cur.getColumnIndex("m_id"));

                        if (!buffer.contains(mid)) {
                            continue;
                        }

                        String subj = cur.getString(cur.getColumnIndex("sub"));
                        String body = "";
                        String from = getMmsAddr(context, id);
                        long date = Long.parseLong(cur.getString(cur.getColumnIndex("date")));

                        Cursor cprt = context.getContentResolver().query(Uri.parse("content://mms/part"), null, "mid = " + id, null, null);
                        try {
                            if (cprt.moveToFirst()) {
                                do {
                                    String pid = cprt.getString(cprt.getColumnIndex("_id"));
                                    String type = cprt.getString(cprt.getColumnIndex("ct"));
                                    if ("text/plain".equals(type)) {
                                        String dat = cprt.getString(cprt.getColumnIndex("_data"));
                                        if (dat != null) {
                                            body += getMmsText(context, pid);
                                        } else {
                                            body += cprt.getString(cprt.getColumnIndex("text"));
                                        }
                                    } else if ("image/jpeg".equals(type) || "image/bmp".equals(type) || "image/gif".equals(type) || "image/jpg".equals(type) || "image/png".equals(type)) {
                                        body += "\n[image]\n";
                                    }
                                } while (cprt.moveToNext());
                            }
                        } finally {
                            if (cprt != null) {
                                cprt.close();
                            }
                        }

                        messages.add(new TextMessage(from, date, subj + (body.length() != 0 ? "\n" + body : "")));
                        return messages;
                    } while (cur.moveToNext() && ++cnt < 10);
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ex) {
            return messages;
        }

        return messages;
    }

    private static String getMmsText(Context context, String id)
    {
        InputStream is = null;
        StringBuilder sb = new StringBuilder();

        try {
            is = context.getContentResolver().openInputStream(Uri.parse("content://mms/part/" + id));
            if (is == null) {
                return sb.toString();
            }

            InputStreamReader isr = new InputStreamReader(is, "UTF-8");
            BufferedReader reader = new BufferedReader(isr);
            String temp = reader.readLine();
            while (temp != null) {
                sb.append(temp);
                temp = reader.readLine();
            }
        } catch (IOException e) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException e) { }
            }
        }

        return sb.toString();
    }

    private static String getMmsAddr(Context context, int id)
    {
        String address = "";
        String val;

        Cursor cur = context.getContentResolver().query(Uri.parse("content://mms/" + id + "/addr"), new String[] { "address" }, "type=137 AND msg_id=" + id, null, null);

        if (cur == null) {
            return address;
        }

        try {
            if (cur.moveToFirst()) {
                do {
                    val = cur.getString(cur.getColumnIndex("address"));
                    if (val != null) {
                        address = val;
                        break;
                    }
                } while (cur.moveToNext());
            }
        } finally {
            cur.close();
        }

        // return address.replaceAll("[^0-9]", "");
        return address;
    }

}