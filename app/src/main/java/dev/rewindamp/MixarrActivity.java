package dev.rewindamp;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/** Explicit online discovery and confirmation; media acquisition belongs to Mixarr. */
public final class MixarrActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=Executors.newSingleThreadExecutor();
    private final Set<String> submitting=new HashSet<>();
    private final java.util.Map<Long,TextView> jobLabels=new java.util.HashMap<>();
    private final java.util.Map<Long,ProgressBar> jobBars=new java.util.HashMap<>();
    private LinearLayout root,results,jobs;
    private EditText query;
    private TextView status,activitySummary;
    private ScrollView resultScroll;
    private boolean activityMode;
    private Button searchButton;
    private final java.util.Map<String,TextView> importLabels=new java.util.HashMap<>();
    private MixarrApi api;
    private String scope,initial="";
    private int generation;
    private boolean destroyed,resumed,polling;
    private JSONArray recordings=new JSONArray();
    private final Runnable poll=this::poll;
    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    TextView text(String label,int size,int color){TextView v=new TextView(this);v.setText(label);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Retro.MONO);return v;}
    Button button(String label,Runnable action){Button b=new Retro.MetalButton(this,label);b.setOnClickListener(v->action.run());return b;}
    void addButton(LinearLayout box,String label,Runnable action){box.addView(button(label,action),new LinearLayout.LayoutParams(-1,dp(48)));}
    EditText field(String hint,boolean password){EditText v=new EditText(this);v.setSingleLine();v.setHint(hint);v.setTextColor(Retro.GREEN);v.setHintTextColor(Retro.MUTED);v.setTextSize(14);v.setTypeface(Retro.MONO);v.setBackground(Retro.bevel(Retro.LCD,true));v.setContentDescription(hint);v.setPadding(dp(12),dp(8),dp(12),dp(8));if(password)v.setInputType(129);return v;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        JellyfinApi jellyfin=SessionStore.load(this);if(jellyfin==null){finish();return;}
        try{scope=MixarrApi.scope(jellyfin);}catch(Exception e){finish();return;}
        initial=state==null?getIntent().getStringExtra("query"):state.getString("query","");if(initial==null)initial="";
        activityMode=state==null?getIntent().getBooleanExtra("activity",false):state.getBoolean("activity");
        api=MixarrApi.load(this,scope);
        if(api==null)connect();else{screen();if(!activityMode&&state==null&&!initial.isBlank()&&!MixarrYouTubeImport.isPlaylist(initial))search();}
    }
    void base(String title){
        root=column();root.setBackground(Retro.bevel(Retro.BG,false));root.setPadding(dp(8),dp(8),dp(8),dp(8));
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){var p=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());root.setPadding(dp(8)+p.left,dp(8)+p.top,dp(8)+p.right,dp(8)+p.bottom);}else root.setPadding(dp(8)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(8)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());if(android.os.Build.VERSION.SDK_INT>=30){View intro=root.findViewWithTag("search-intro");if(intro!=null)intro.setVisibility(insets.isVisible(android.view.WindowInsets.Type.ime())?View.GONE:View.VISIBLE);}return insets;});
        setContentView(root);root.setFocusableInTouchMode(true);root.requestFocus();root.addView(new Retro.Rail(this,title),new LinearLayout.LayoutParams(-1,dp(24)));
        LinearLayout navigation=new LinearLayout(this);navigation.addView(Retro.action(this,"‹ Back",this::finish),new LinearLayout.LayoutParams(0,dp(48),1));if(!title.contains("CONNECT"))navigation.addView(Retro.action(this,"Connection",()->{if(query!=null)initial=query.getText().toString();connect();}),new LinearLayout.LayoutParams(-2,dp(48)));root.addView(navigation);
    }
    void connect(){
        generation++;handler.removeCallbacks(poll);jobs=null;
        base("REWINDAMP / CONNECT MIXARR");ScrollView scroll=new ScrollView(this);LinearLayout form=column();scroll.addView(form);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        form.setPadding(dp(8),0,dp(8),dp(8));form.addView(Retro.heading(this,"Connect Mixarr","Use your Mixarr account to add music to your library."));
        EditText address=field("Mixarr HTTPS address",false),username=field("Mixarr username",false),password=field("Mixarr password",true);address.setText(api==null?"https://mixarr.snappyapps.com.au":api.server);
        for(EditText input:new EditText[]{address,username,password}){LinearLayout.LayoutParams fieldSpace=new LinearLayout.LayoutParams(-1,dp(52));fieldSpace.bottomMargin=dp(12);form.addView(input,fieldSpace);}
        TextView error=text("Your password is used only to sign in. Searches never start downloads.",12,Retro.GOLD);form.addView(error);
        if(api!=null)addButton(form,"DISCONNECT MIXARR",()->{
            MixarrApi previous=api;MixarrApi.clear(this,scope);api=null;connect();
            io.execute(()->{try{previous.request("/api/v1/logout",new JSONObject());}catch(Exception ignored){}});
        });
        Button signIn=button("CONNECT MIXARR",()->{
            String url=address.getText().toString().trim(),name=username.getText().toString().trim(),secret=password.getText().toString();password.setText("");
            if(name.isBlank()||secret.isEmpty()){error.setText("Enter your Mixarr username and password.");return;}
            error.setText("Connecting…");address.setEnabled(false);username.setEnabled(false);
            // Prevent repeated login taps while the request is running.
            form.getChildAt(form.getChildCount()-1).setEnabled(false);
            io.execute(()->{try{
                MixarrApi connected=MixarrApi.login(url,name,secret);MixarrApi.save(getApplicationContext(),scope,connected);
                handler.post(()->{if(destroyed)return;api=connected;screen();if(!activityMode&&!initial.isBlank()&&!MixarrYouTubeImport.isPlaylist(initial))search();});
            }catch(Exception e){handler.post(()->{if(destroyed)return;error.setText(e instanceof IllegalArgumentException?"Enter a full HTTPS Mixarr address.":MixarrApi.message(e));address.setEnabled(true);username.setEnabled(true);form.getChildAt(form.getChildCount()-1).setEnabled(true);});}});
        });form.addView(signIn,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    void screen(){
        generation++;base(activityMode?"REWINDAMP / ACTIVITY":"REWINDAMP / ADD MUSIC");
        query=null;activitySummary=null;recordings=new JSONArray();jobLabels.clear();jobBars.clear();importLabels.clear();
        if(activityMode){
            root.addView(Retro.heading(this,"Library activity","Follow your music requests and imports."));
            status=text("Requests continue while you browse or listen.",12,Retro.MUTED);status.setPadding(dp(8),0,dp(8),dp(12));root.addView(status);
            resultScroll=new ScrollView(this);jobs=column();resultScroll.addView(jobs);root.addView(resultScroll,new LinearLayout.LayoutParams(-1,0,1));results=column();
            root.addView(Retro.action(this,"+ Find more music",()->{activityMode=false;initial="";screen();}),new LinearLayout.LayoutParams(-1,dp(48)));
        }else{
            TextView intro=Retro.heading(this,"Find new music","Search online to add music to your server library.");intro.setTag("search-intro");root.addView(intro);
            query=field("Song, artist, album or YouTube link",false);query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});query.setText(initial);root.addView(query,new LinearLayout.LayoutParams(-1,dp(52)));
            query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);query.setOnEditorActionListener((v,a,event)->{if(a==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
            searchButton=button("SEARCH ONLINE",this::search);root.addView(searchButton,new LinearLayout.LayoutParams(-1,dp(48)));updateSearchButton();
            status=text("Choose a recording to add it. YouTube links can be previewed here too.",12,Retro.MUTED);status.setPadding(dp(8),dp(10),dp(8),dp(10));status.setMinHeight(dp(48));root.addView(status);
            resultScroll=new ScrollView(this);results=column();resultScroll.addView(results);root.addView(resultScroll,new LinearLayout.LayoutParams(-1,0,1));jobs=column();
            activitySummary=Retro.action(this,"Library activity  ›",()->startActivity(new android.content.Intent(this,MixarrActivity.class).putExtra("activity",true)));activitySummary.setContentDescription("Open library request activity");activitySummary.setSingleLine();activitySummary.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);activitySummary.setMarqueeRepeatLimit(-1);activitySummary.setSelected(true);activitySummary.setBackgroundColor(Retro.PANEL);root.addView(activitySummary,new LinearLayout.LayoutParams(-1,dp(48)));
            results.addView(Retro.heading(this,"Discover something new","Try a song and artist, an album title, or paste a YouTube link."));
            query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){generation++;recordings=new JSONArray();results.removeAllViews();updateSearchButton();status.setText("Search music or paste a YouTube link.");}});
        }
        buildJobs();handler.removeCallbacks(poll);if(resumed)handler.post(poll);
    }
    void search(){
        if(api==null||query==null)return;
        String term=query.getText().toString().trim();if(MixarrYouTubeImport.isLink(term)){youtube(term);return;}if(term.length()>300){status.setText("Music searches can contain at most 300 characters.");return;}if(term.isEmpty()){status.setText("Enter a song, artist or album name.");return;}
        int request=++generation;MixarrApi client=api;recordings=new JSONArray();results.removeAllViews();status.setText("Searching MusicBrainz through Mixarr…");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
        io.execute(()->{try{JSONArray rows=(JSONArray)client.request("/api/v1/track-requests/search",new JSONObject().put("query",term));handler.post(()->{if(destroyed||request!=generation||api!=client)return;recordings=rows;status.setText(rows.length()==0?"No recordings found. Try another song, artist or album.":rows.length()+(rows.length()==1?" recording":" recordings")+" · Review the version before adding.");renderResults();});}
            catch(Exception e){handler.post(()->{if(!destroyed&&request==generation)status.setText(MixarrApi.message(e));});}});
    }
    void updateSearchButton(){String term=query.getText().toString().trim();searchButton.setText(MixarrYouTubeImport.isLink(term)?MixarrYouTubeImport.isPlaylist(term)?"DOWNLOAD ENTIRE PLAYLIST":"PREVIEW YOUTUBE IMPORT":"SEARCH ONLINE");}
    void youtube(String url){
        if(!MixarrYouTubeImport.validLink(url)){status.setText("Paste an HTTPS YouTube video or playlist link.");return;}
        if(MixarrYouTubeImport.isPlaylist(url)&&!submitting.add("playlist:"+url))return;
        int request=++generation;MixarrApi client=api;results.removeAllViews();recordings=new JSONArray();
        boolean playlist=MixarrYouTubeImport.isPlaylist(url);
        status.setText(playlist?"Queuing all available playlist tracks…":"Reading the YouTube link…");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
        io.execute(()->{try{
            JSONObject response=(JSONObject)client.request(playlist?"/api/v1/youtube-imports/playlist":"/api/v1/youtube-imports/preview",new JSONObject().put("url",url));
            if(playlist){response.put("import_id",response.getString("id"));MixarrApi.remember(getApplicationContext(),scope,client,response);}
            handler.post(()->{submitting.remove("playlist:"+url);if(destroyed||client!=api)return;
                if(jobs!=null)buildJobs();
                if(request==generation){status.setText(playlist?"Playlist queued. Tap Library activity to follow progress.":"Review names and chapter times, then import selected audio.");if(!playlist)new MixarrYouTubeImport(this,response,results,body->submitImport(client,request,body));}
                handler.removeCallbacks(poll);if(resumed)handler.post(poll);
            });
        }catch(Exception e){handler.post(()->{submitting.remove("playlist:"+url);if(!destroyed&&client==api&&request==generation)status.setText(MixarrApi.message(e));});}});
    }
    void submitImport(MixarrApi client,int request,JSONObject body){
        String key="preview:"+request;if(!submitting.add(key))return;
        status.setText("Queuing selected audio…");
        io.execute(()->{try{
            JSONObject batch=(JSONObject)client.request("/api/v1/youtube-imports",body);batch.put("import_id",batch.getString("id"));MixarrApi.remember(getApplicationContext(),scope,client,batch);
            handler.post(()->{submitting.remove(key);if(destroyed||client!=api)return;if(jobs!=null)buildJobs();if(request==generation){results.removeAllViews();status.setText("Audio import queued. Tap Library activity to follow progress.");}handler.removeCallbacks(poll);if(resumed)handler.post(poll);});
        }catch(Exception e){handler.post(()->{submitting.remove(key);if(!destroyed&&client==api&&request==generation)status.setText(MixarrApi.message(e)+" You can confirm again to retry.");});}});
    }
    static boolean pending(JSONObject row){
        if(!row.has("import_id"))return active(row.optString("state"));
        JSONArray items=row.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++)if(active(items.optJSONObject(i).optString("state")))return true;return false;
    }
    void updateImport(JSONObject batch){
        TextView label=importLabels.get(batch.optString("import_id"));if(label==null)return;
        StringBuilder summary=new StringBuilder();JSONArray items=batch.optJSONArray("items");
        if(batch.optInt("skipped")>0)summary.append(batch.optInt("skipped")).append(" unavailable or repeated entries skipped.\n");
        if(items!=null)for(int i=0;i<items.length();i++){JSONObject row=items.optJSONObject(i);summary.append(row.optString("title")).append(" — ").append(row.optString("artist")).append(" · ").append(row.optString("state")).append('\n');}
        summary.append("Full download progress and failed-import controls are in Mixarr Activity.");label.setText(summary);
    }
    void renderResults(){
        int scroll=resultScroll.getScrollY();results.removeAllViews();
        for(int i=0;i<recordings.length();i++){
            JSONObject item=recordings.optJSONObject(i);if(item==null)continue;
            LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(14),dp(6),dp(14));row.setBackgroundColor(Retro.LCD);
            LinearLayout info=column();TextView title=text(item.optString("title"),16,Retro.GREEN);title.setMaxLines(2);info.addView(title);info.addView(text(item.optString("artist"),13,Retro.WHITE));
            String album=item.isNull("album")?"Album unknown":item.optString("album");long duration=item.optLong("duration_ms");
            info.addView(text(album+" · "+(duration>0?String.format(Locale.ROOT,"%d:%02d",duration/60000,duration/1000%60):"Duration unknown"),12,Retro.MUTED));
            if(!item.optString("version").isBlank())info.addView(text(item.optString("version"),12,Retro.GOLD));
            row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            Button add=button(submitting.contains(item.optString("id"))?"ADDING…":"ADD",()->confirm(item));add.setContentDescription("Add recording: "+item.optString("title")+" by "+item.optString("artist"));add.setEnabled(!submitting.contains(item.optString("id")));row.addView(add,new LinearLayout.LayoutParams(dp(64),dp(48)));results.addView(row);Retro.divider(results);
        }
        resultScroll.post(()->resultScroll.scrollTo(0,scroll));
    }
    void confirm(JSONObject item){
        String id=item.optString("id");if(!submitting.add(id))return;renderResults();status.setText("Asking Mixarr to add this recording…");
        MixarrApi client=api;Context context=getApplicationContext();int request=generation;
        io.execute(()->{try{
            JSONObject response=(JSONObject)client.request("/api/v1/track-requests",new JSONObject().put("token",item.getString("token")));
            response.put("title",item.optString("title")).put("artist",item.optString("artist"));MixarrApi.remember(context,scope,client,response);
            handler.post(()->{if(destroyed||client!=api)return;submitting.remove(id);buildJobs();if(request==generation){status.setText(response.optString("state").equals("available")?"Already available in your server library.":"Request added. Keep searching; progress is in Library activity.");renderResults();}handler.removeCallbacks(poll);if(resumed)handler.post(poll);});
        }catch(Exception e){handler.post(()->{if(destroyed||client!=api)return;submitting.remove(id);if(request==generation){status.setText(MixarrApi.message(e));renderResults();}});}});
    }
    static boolean active(String state){return state.equals("queued")||state.equals("processing")||state.equals("paused");}
    void buildJobs(){
        jobs.removeAllViews();jobLabels.clear();jobBars.clear();importLabels.clear();JSONArray history=api.history(this,scope);
        if(activitySummary!=null)activitySummary.setText(AcquisitionStatusView.summary(history));
        if(!activityMode)return;
        if(history.length()==0){jobs.addView(Retro.heading(this,"Nothing waiting","Music requests will appear here after you add a recording or import audio."));return;}
        jobs.addView(text("RECENT LIBRARY REQUESTS",12,Retro.GOLD));
        for(int i=0;i<history.length();i++){
            JSONObject row=history.optJSONObject(i);if(row==null)continue;
            LinearLayout card=column();card.setPadding(dp(12),dp(14),dp(12),dp(14));card.setBackgroundColor(Retro.LCD);LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);space.topMargin=dp(8);jobs.addView(card,space);
            if(row.has("import_id")){card.addView(text("YouTube import: "+row.optString("title"),14,Retro.GREEN));TextView label=text("",12,Retro.MUTED);card.addView(label);importLabels.put(row.optString("import_id"),label);updateImport(row);continue;}
            long track=row.optLong("track_id");
            card.addView(text(row.optString("title")+" — "+row.optString("artist"),13,Retro.GREEN));TextView label=text("",12,Retro.MUTED);card.addView(label);jobLabels.put(track,label);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);card.addView(bar,new LinearLayout.LayoutParams(-1,dp(12)));jobBars.put(track,bar);updateJob(row);
            if(row.optString("state").equals("failed")||row.optString("state").equals("cancelled"))addButton(card,"SEARCH AGAIN TO RETRY",()->{initial=row.optString("title")+" "+row.optString("artist");activityMode=false;screen();search();});

        }
    }
    void updateJob(JSONObject row){
        long id=row.optLong("track_id");TextView label=jobLabels.get(id);ProgressBar bar=jobBars.get(id);if(label==null||bar==null)return;
        String state=row.optString("state"),caption=switch(state){case "queued"->"Queued";case "processing"->"Processing";case "paused"->"Paused · waiting for Mixarr to resume";case "completed"->"Completed · return to library search to find your track";case "available"->"Already available";case "failed"->"Failed · search again to retry";case "cancelled"->"Cancelled · check Mixarr before retrying";default->"Status unknown";};
        JSONObject progress=row.optJSONObject("progress");boolean measurable=false;
        if(state.equals("processing")&&progress!=null){caption=progress.optString("label","Processing");double percent=progress.optDouble("percent",Double.NaN);measurable=Double.isFinite(percent)&&percent>=0&&percent<=100;if(measurable){bar.setProgress((int)percent);caption+=String.format(Locale.ROOT," · %.1f%% of current stream",percent);}if(progress.has("downloaded_bytes"))caption+=" · "+android.text.format.Formatter.formatShortFileSize(this,progress.optLong("downloaded_bytes"));if(progress.has("speed"))caption+=" · "+android.text.format.Formatter.formatShortFileSize(this,progress.optLong("speed"))+"/s";if(progress.has("eta"))caption+=" · ETA "+progress.optLong("eta")+"s";}
        label.setText(caption);bar.setVisibility(active(state)?View.VISIBLE:View.GONE);bar.setIndeterminate(!measurable);
    }
    void poll(){
        if(!resumed||destroyed||api==null||jobs==null||polling)return;
        JSONArray history=api.history(this,scope);boolean any=false;for(int i=0;i<history.length();i++)if(pending(history.optJSONObject(i)))any=true;if(!any)return;
        polling=true;MixarrApi client=api;Context context=getApplicationContext();
        io.execute(()->{boolean rebuild=false;String error=null;try{
            for(int i=0;i<history.length();i++){JSONObject old=history.getJSONObject(i);
                if(old.has("import_id")){if(pending(old)){JSONObject batch=(JSONObject)client.request("/api/v1/youtube-imports/"+android.net.Uri.encode(old.getString("import_id")),null);batch.put("import_id",old.getString("import_id"));if(old.has("skipped"))batch.put("skipped",old.getInt("skipped"));MixarrApi.remember(context,scope,client,batch);}continue;}
                if(!active(old.optString("state"))||old.isNull("job_id"))continue;
                JSONObject next=(JSONObject)client.request("/api/v1/track-requests/jobs/"+old.getLong("job_id"),null);next.put("title",old.optString("title")).put("artist",old.optString("artist"));
                if(!active(next.optString("state")))rebuild=true;MixarrApi.remember(context,scope,client,next);
            }
        }catch(Exception e){error=MixarrApi.message(e);}
            boolean refresh=rebuild;String failure=error;handler.post(()->{polling=false;if(destroyed||client!=api)return;if(failure!=null)status.setText(failure+" Status will retry automatically.");if(activitySummary!=null)activitySummary.setText(AcquisitionStatusView.summary(api.history(this,scope)));if(refresh)buildJobs();else{JSONArray rows=api.history(this,scope);for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row.has("import_id"))updateImport(row);else updateJob(row);}}if(resumed)handler.postDelayed(poll,5000);});
        });
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(api!=null&&jobs!=null)buildJobs();handler.removeCallbacks(poll);handler.post(poll);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("query",query==null?initial:query.getText().toString());state.putBoolean("activity",activityMode);}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy();}
}
