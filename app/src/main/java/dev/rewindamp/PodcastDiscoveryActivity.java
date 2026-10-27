package dev.rewindamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;

/** Apple is a discovery catalogue; all subscriptions and listening state stay on our server. */
public final class PodcastDiscoveryActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=Executors.newFixedThreadPool(2);
    private PodcastApi api;
    private EditText query;
    private LinearLayout root,results;
    private TextView status;
    private boolean destroyed,subscribing;
    private int generation;
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Retro.MONO);return v;}
    Button button(String label,Runnable action){Button v=new Retro.MetalButton(this,label);v.setOnClickListener(x->action.run());return v;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);api=PodcastApi.load(this);if(api==null){finish();return;}
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=column();root.setBackground(Retro.bevel(Retro.BG,false));root.setPadding(dp(8),dp(8),dp(8),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){var p=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());root.setPadding(dp(8)+p.left,dp(8)+p.top,dp(8)+p.right,dp(8)+p.bottom);}else root.setPadding(dp(8)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(8)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;});setContentView(root);
        root.addView(new Retro.Rail(this,"REWINDAMP / FIND PODCASTS"),new LinearLayout.LayoutParams(-1,dp(24)));
        root.addView(text("APPLE PODCASTS · AU CATALOGUE",11,Retro.GOLD),new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout search=new LinearLayout(this);search.setGravity(Gravity.CENTER_VERTICAL);query=new EditText(this);query.setSingleLine();query.setHint("Podcast title or publisher");query.setContentDescription("Podcast search");query.setTextColor(Retro.GREEN);query.setHintTextColor(Retro.MUTED);query.setTypeface(Retro.MONO);query.setTextSize(14);query.setBackground(Retro.bevel(Retro.LCD,true));query.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.addView(query,new LinearLayout.LayoutParams(0,dp(48),1));search.addView(button("GO",this::search),new LinearLayout.LayoutParams(dp(60),dp(48)));root.addView(search);
        query.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        status=text("Search for a show, then choose which episodes to download.",12,Retro.MUTED);root.addView(status,new LinearLayout.LayoutParams(-1,dp(48)));
        ScrollView scroll=new ScrollView(this);results=column();results.setBackground(Retro.bevel(Retro.LCD,true));scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));root.addView(Retro.navigationBar(this,true));
        if(state!=null)query.setText(state.getString("query",""));
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("query",query.getText().toString());}
    void search(){
        String term=query.getText().toString().trim();if(term.length()<2||term.length()>120){status.setText("Enter 2–120 characters.");return;}
        int request=++generation;status.setText("SEARCHING APPLE PODCASTS…");results.removeAllViews();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
        io.execute(()->{try{JSONObject data=api.request("/api/podcasts/search?term="+android.net.Uri.encode(term),null);handler.post(()->{if(destroyed||request!=generation)return;render(data);});}
        catch(Exception e){handler.post(()->{if(destroyed||request!=generation)return;String message=e.getMessage();status.setText(message!=null&&message.contains("429")?"Search is busy. Try again in a minute.":"Search unavailable. Retry, or add an RSS feed in Podcast settings.");});}});
    }
    void render(JSONObject data){
        results.removeAllViews();var items=data.optJSONArray("items");int count=items==null?0:items.length();status.setText(count==0?"NO RESULTS · Try another title or publisher.":count+" SHOWS · Choose a show to subscribe.");
        for(int i=0;i<count;i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;LinearLayout row=column();row.setPadding(dp(10),dp(10),dp(10),dp(12));TextView title=text(item.optString("title"),15,Retro.GREEN);row.addView(title);row.addView(text(item.optString("author"),12,Retro.MUTED));row.addView(text(item.optString("genre"),10,Retro.GOLD));
            boolean subscribed=item.optBoolean("subscribed");Button add=button(subscribed?"ALREADY SUBSCRIBED":"+ SUBSCRIBE",()->choosePass(item));add.setEnabled(!subscribed&&!subscribing);row.addView(add,new LinearLayout.LayoutParams(-1,dp(42)));results.addView(row);
        }
    }
    void choosePass(JSONObject item){
        if(subscribing)return;int[] chosen={0};new AlertDialog.Builder(this).setTitle("Subscribe: "+item.optString("title")).setSingleChoiceItems(new String[]{"Future episodes only","Last 12 months","Last two years","This year (from January 1)","All available episodes (back catalogue)","Manual downloads only"},0,(d,i)->chosen[0]=i).setNegativeButton("Cancel",null).setPositiveButton("Subscribe",(d,w)->subscribe(item,chosen[0])).show();
    }
    void subscribe(JSONObject item,int choice){
        if(subscribing)return;subscribing=true;status.setText("ADDING SUBSCRIPTION…");
        io.execute(()->{try{String mode=switch(choice){case 1,2->"years";case 3->"since";case 4->"all";case 5->"manual";default->"future";};JSONObject body=new JSONObject().put("title",item.getString("title")).put("url",item.getString("feed_url")).put("mode",mode).put("years",choice==2?2:1).put("only_if_new",true);
            if(choice==3){java.util.Calendar start=java.util.Calendar.getInstance();start.set(java.util.Calendar.DAY_OF_YEAR,1);start.set(java.util.Calendar.HOUR_OF_DAY,0);start.set(java.util.Calendar.MINUTE,0);start.set(java.util.Calendar.SECOND,0);start.set(java.util.Calendar.MILLISECOND,0);body.put("since",start.getTimeInMillis()/1000);}
            JSONObject added=api.request("/api/feeds",body);handler.post(()->{if(destroyed)return;setResult(RESULT_OK,new Intent().putExtra("feed_id",added.optString("id")));finish();});
        }catch(Exception e){handler.post(()->{if(destroyed)return;subscribing=false;status.setText("Could not add this feed. Retry when connected.");});}});
    }
    @Override protected void onDestroy(){destroyed=true;generation++;handler.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy();}
}
