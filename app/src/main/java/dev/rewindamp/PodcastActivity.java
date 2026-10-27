package dev.rewindamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Spoken audio uses the same metal rails, LCD colours and transport styling as the music deck. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PodcastActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=Executors.newSingleThreadExecutor();
    private PodcastApi api;
    private PodcastLibrary library;
    private MediaController player;
    private ListenableFuture<MediaController> future;
    private LinearLayout root,rows;
    private TextView status,title,artist;
    private Retro.Digits clock;
    private SeekBar seek;
    private Spinner shows;
    private final List<String> feedIds=new ArrayList<>();
    private final List<JSONObject> episodes=new ArrayList<>();
    private String feed=null,pendingFeedId=null;
    private boolean inbox=true,phone=false,loading=false,more=false,destroyed=false;
    private int generation;
    private final Runnable ticker=new Runnable(){@Override public void run(){update();if(!destroyed)handler.postDelayed(this,500);}};
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Retro.MONO);return v;}
    Button button(String value,Runnable action){Button v=new Retro.MetalButton(this,value);v.setOnClickListener(x->action.run());return v;}
    void weighted(LinearLayout parent,View child,int height){parent.addView(child,new LinearLayout.LayoutParams(0,dp(height),1));}
    void rail(String value){root.addView(new Retro.Rail(this,value),new LinearLayout.LayoutParams(-1,dp(24)));}
    @Override public void onCreate(Bundle state){super.onCreate(state);api=PodcastApi.load(this);if(api==null)connectScreen();else{library=PodcastLibrary.get(this,api);screen();connectPlayer();loadFeeds();}handler.post(ticker);}
    void base(){
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=column();root.setPadding(dp(8),dp(8),dp(8),dp(8));root.setBackground(Retro.bevel(Retro.BG,false));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){var padding=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());root.setPadding(dp(8)+padding.left,dp(8)+padding.top,dp(8)+padding.right,dp(8)+padding.bottom);}
            else root.setPadding(dp(8)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(8)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;
        });setContentView(root);rail("REWINDAMP / PODCASTS");
    }
    EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setTextColor(Retro.GREEN);e.setHintTextColor(Retro.MUTED);e.setTypeface(Retro.MONO);e.setTextSize(13);e.setSingleLine();e.setHint(hint);e.setBackground(Retro.bevel(Retro.LCD,true));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(secret?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_VARIATION_URI));if(secret)e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return e;}
    void connectScreen(){base();root.addView(text("PODCAST SERVER",24,Retro.GREEN));root.addView(text("Independent subscriptions, archive and listening history.\nUse HTTPS outside your trusted network.",12,Retro.MUTED));
        EditText server=input("https://podcasts.example.com",false),token=input("Owner token",true);root.addView(server,new LinearLayout.LayoutParams(-1,dp(56)));root.addView(token,new LinearLayout.LayoutParams(-1,dp(56)));
        status=text("",12,Retro.GOLD);Button connect=button("CONNECT",()->{});connect.setOnClickListener(v->{connect.setEnabled(false);String address=server.getText().toString(),secret=token.getText().toString();io.execute(()->{try{PodcastApi candidate=new PodcastApi(address,secret);candidate.request("/api/feeds",null);PodcastApi.save(this,candidate);handler.post(()->{if(destroyed)return;token.setText("");api=candidate;library=PodcastLibrary.get(this,api);screen();connectPlayer();loadFeeds();});}catch(Exception e){handler.post(()->{connect.setEnabled(true);say("Connection failed. Check the HTTPS address and owner token.");});}});});root.addView(connect,new LinearLayout.LayoutParams(-1,dp(48)));root.addView(status);root.addView(button("‹ MUSIC",this::finish));}
    void screen(){base();LinearLayout lcd=column();lcd.setPadding(dp(12),dp(10),dp(12),dp(10));lcd.setBackground(Retro.bevel(Retro.LCD,true));title=text("READY WHEN YOU ARE",15,Retro.GREEN);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);title.setMarqueeRepeatLimit(-1);title.setSelected(true);lcd.addView(title);clock=new Retro.Digits(this);lcd.addView(clock,new LinearLayout.LayoutParams(-1,dp(45)));artist=text("PODCASTS · SPOKEN AUDIO",11,Retro.MUTED);artist.setSingleLine();lcd.addView(artist);root.addView(lcd);
        seek=new SeekBar(this);seek.setMax(1000);seek.setThumb(Retro.thumb(this));seek.setProgressTintList(ColorStateList.valueOf(Retro.GOLD));seek.setContentDescription("Episode position");seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){}public void onStopTrackingTouch(SeekBar s){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*s.getProgress()/1000);}});root.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout transport=row();weighted(transport,button("−15 SEC",()->{if(player!=null)player.seekBack();}),48);weighted(transport,button("PLAY",()->{if(player!=null){if(player.getPlaybackState()==Player.STATE_IDLE)player.prepare();player.play();}}),48);weighted(transport,button("PAUSE",()->{if(player!=null)player.pause();}),48);weighted(transport,button("+30 SEC",()->{if(player!=null)player.seekForward();}),48);root.addView(transport);
        LinearLayout controls=row();weighted(controls,button("SPEED",()->new AlertDialog.Builder(this).setTitle("Podcast speed").setItems(new String[]{"0.75×","1×","1.25×","1.5×","2×"},(d,i)->{if(player!=null)player.setPlaybackSpeed(new float[]{.75f,1f,1.25f,1.5f,2f}[i]);}).show()),38);weighted(controls,button("PLAYED",()->{if(player!=null&&player.getCurrentMediaItem()!=null)mark(library.episode(player.getCurrentMediaItem().mediaId),"played");}),38);weighted(controls,button("SET",this::settings),38);weighted(controls,button("‹ MUSIC",this::finish),38);root.addView(controls);rail("PODCAST LIBRARY");
        root.addView(button("+ FIND PODCASTS",()->startActivityForResult(new Intent(this,PodcastDiscoveryActivity.class),77)),new LinearLayout.LayoutParams(-1,dp(38)));
        shows=new Spinner(this);shows.setContentDescription("Podcast show");root.addView(shows,new LinearLayout.LayoutParams(-1,dp(44)));shows.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){feed=index<feedIds.size()?feedIds.get(index):null;phone=false;inbox=false;load(false);}});
        LinearLayout tabs=row();weighted(tabs,button("UNPLAYED",()->{feed=null;inbox=true;phone=false;load(false);}),40);weighted(tabs,button("PHONE",()->{phone=true;load(false);}),40);weighted(tabs,button("PASS",this::pass),40);weighted(tabs,button("↻",()->load(false)),40);root.addView(tabs);
        ScrollView scroll=new ScrollView(this);rows=column();rows.setBackground(Retro.bevel(Retro.LCD,true));scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));status=text("Ready · choose a show or Unplayed",11,Retro.GOLD);root.addView(status,new LinearLayout.LayoutParams(-1,dp(42)));}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==77&&result==RESULT_OK&&library!=null){pendingFeedId=data==null?null:data.getStringExtra("feed_id");loadFeeds();say("Subscribed · the server will check the feed shortly");}}
    void connectPlayer(){
        future=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PodcastPlaybackService.class))).buildAsync();
        future.addListener(()->{try{if(destroyed)return;player=future.get();update();}catch(Exception e){say("Could not connect to podcast playback");}},task->handler.post(task));
    }
    void update(){if(player==null||clock==null)return;long position=Math.max(0,player.getCurrentPosition());clock.setTime(Track.time(position));if(player.getCurrentMediaItem()!=null){var meta=player.getCurrentMediaItem().mediaMetadata;title.setText(meta.title);artist.setText(meta.artist+" · "+player.getPlaybackParameters().speed+"×");}long duration=player.getDuration();if(!seek.isPressed())seek.setProgress(duration>0?(int)(1000*position/duration):0);}
    void loadFeeds(){io.execute(()->{try{var feeds=library.feeds();List<String> labels=new ArrayList<>();List<String> ids=new ArrayList<>();labels.add("ALL SHOWS");ids.add(null);for(int i=0;i<feeds.length();i++){JSONObject f=feeds.getJSONObject(i);labels.add(f.getString("title"));ids.add(f.getString("id"));}handler.post(()->{if(destroyed)return;feedIds.clear();feedIds.addAll(ids);var adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);shows.setAdapter(adapter);if(pendingFeedId!=null){int selected=ids.indexOf(pendingFeedId);pendingFeedId=null;if(selected>=0)shows.setSelection(selected);}});}catch(Exception e){handler.post(()->say("Server unavailable · phone downloads remain playable"));}});}
    void load(boolean append){if(loading&&append)return;int request=++generation;loading=true;String wanted=feed;boolean wantInbox=inbox,wantPhone=phone;int offset=append?episodes.size():0;
        io.execute(()->{try{library.sync();List<JSONObject> list=wantPhone?library.downloaded():library.episodes(wanted,wantInbox,offset);handler.post(()->{if(destroyed||request!=generation)return;loading=false;if(!append)episodes.clear();episodes.addAll(list);more=!wantPhone&&list.size()==100;render();say((wantPhone?"PHONE DOWNLOADS":wantInbox?"UNPLAYED":"SHOW EPISODES")+" · "+episodes.size()+" EPISODES");});}catch(Exception e){handler.post(()->{if(destroyed||request!=generation)return;loading=false;say("Server unavailable · tap PHONE for downloaded episodes");});}});
    }
    void render(){rows.removeAllViews();if(episodes.isEmpty())rows.addView(text("NO EPISODES HERE\n\nFuture-only shows start with an empty inbox.\nRefresh after the next release.",12,Retro.MUTED));for(JSONObject episode:episodes){String id=episode.optString("id");JSONObject local=library.episode(id);LinearLayout item=column();item.setPadding(dp(8),dp(8),dp(8),dp(8));TextView label=text(episode.optString("title"),14,Retro.GREEN);label.setMaxLines(2);item.addView(label);String date=episode.optDouble("published")>0?new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(new java.util.Date((long)(episode.optDouble("published")*1000))):"UNDATED";item.addView(text(date+" · "+local.optString("state","unplayed").replace('_',' ')+" · "+Track.time((long)(local.optDouble("position")*1000))+" / "+Track.time((long)(episode.optDouble("duration")*1000)),10,Retro.MUTED));item.addView(text(library.label(id).isBlank()?(episode.optBoolean("downloaded")?"SERVER SAVED":episode.optBoolean("eligible")?"SERVER DOWNLOAD PENDING":"OUTSIDE PASS"):library.label(id),10,Retro.GOLD));LinearLayout actions=row();weighted(actions,button("RESUME",()->play(episode)),36);weighted(actions,button("+",()->episodeMenu(episode)),36);item.addView(actions);rows.addView(item);}if(more)rows.addView(button("MORE EPISODES",()->load(true)));}
    void play(JSONObject episode){if(player==null)return;String id=episode.optString("id");if(!episode.optBoolean("downloaded")&&!library.complete(id)){say("Wait for server download, or choose Download on server");return;}
        JellyfinApi music=SessionStore.load(this);if(music!=null)stopService(new Intent(this,PlaybackService.class));
        JSONObject local=library.episode(id);long start=local.optString("state").equals("played")?0:(long)(local.optDouble("position")*1000);player.setMediaItem(library.media(episode),start);player.prepare();player.play();update();}
    void episodeMenu(JSONObject episode){new AlertDialog.Builder(this).setTitle(episode.optString("title")).setItems(new String[]{"Resume episode","Mark played","Dismiss from inbox","Mark unplayed / reset position","Download copy to phone","Remove copy from phone","Download on server"},(d,i)->{switch(i){case 0->play(episode);case 1->mark(episode,"played");case 2->mark(episode,"dismissed");case 3->mark(episode,"reset");case 4->{try{if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},12);library.download(episode);say("Phone download queued · open PHONE to check");}catch(Exception e){say("Wait for the server to finish downloading this episode first");}}case 5->new AlertDialog.Builder(this).setTitle("Remove phone copy?").setMessage("Listening history and the server archive are retained.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(dd,w)->{library.remove(episode.optString("id"));say("Phone copy removal queued");}).show();case 6->request(()->{api.request("/api/episodes/"+episode.getString("id")+"/download",new JSONObject());return "Server download queued";});}}).show();}
    void mark(JSONObject episode,String action){String id=episode.optString("id");if(id.isBlank())return;long position=(long)(library.episode(id).optDouble("position")*1000);if(player!=null&&player.getCurrentMediaItem()!=null&&player.getCurrentMediaItem().mediaId.equals(id)){player.pause();position=Math.max(0,player.getCurrentPosition());if(action.equals("reset"))player.seekTo(0);}library.event(id,position,action);io.execute(()->library.sync());load(false);}
    void pass(){if(feed==null){say("Choose a show before changing its pass");return;}String id=feed;request(()->{var all=library.feeds();JSONObject found=null;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))found=all.getJSONObject(i);JSONObject target=found;if(target==null)return "Show not found";handler.post(()->new AlertDialog.Builder(this).setTitle("Subscription pass").setItems(new String[]{"Future episodes only","Last 12 months","Last two years","This year (from January 1)","All available episodes","Manual downloads only"},(d,which)->request(()->{String mode=switch(which){case 1,2->"years";case 3->"since";case 4->"all";case 5->"manual";default->"future";};JSONObject body=new JSONObject().put("title",target.getString("title")).put("url",target.getString("url")).put("mode",mode).put("years",which==2?2:1);if(which==3){java.util.Calendar start=java.util.Calendar.getInstance();start.set(java.util.Calendar.DAY_OF_YEAR,1);start.set(java.util.Calendar.HOUR_OF_DAY,0);start.set(java.util.Calendar.MINUTE,0);start.set(java.util.Calendar.SECOND,0);start.set(java.util.Calendar.MILLISECOND,0);body.put("since",start.getTimeInMillis()/1000);}api.request("/api/feeds",body);return "Pass saved · archive and history retained";})).show());return "Choose a subscription pass";});}
    interface Job{String run()throws Exception;}
    void request(Job task){io.execute(()->{try{String message=task.run();handler.post(()->{if(!destroyed)say(message);});}catch(Exception e){handler.post(()->say("Podcast server request failed · retry when connected"));}});}
    void settings(){new AlertDialog.Builder(this).setTitle("Podcast settings").setItems(new String[]{"Sync listening history","Refresh selected show / all shows","Add RSS feed","Disconnect podcast server"},(d,i)->{switch(i){case 0->request(()->{library.sync();return "Sync attempted · pending changes retry while playback service is running";});case 1->request(()->{if(feed!=null)api.request("/api/feeds/"+feed+"/refresh",new JSONObject());else{var feeds=library.feeds();for(int n=0;n<feeds.length();n++)api.request("/api/feeds/"+feeds.getJSONObject(n).getString("id")+"/refresh",new JSONObject());}return "Feed refresh queued · refresh this view shortly";});case 2->{EditText url=input("https://example.com/podcast.rss",false);new AlertDialog.Builder(this).setTitle("Add RSS feed · future episodes only").setView(url).setNegativeButton("Cancel",null).setPositiveButton("Subscribe",(dd,w)->{String address=url.getText().toString().trim();request(()->{api.request("/api/feeds",new JSONObject().put("url",address).put("mode","future").put("only_if_new",true));handler.post(this::loadFeeds);return "Subscribed · initial feed establishes the baseline";});}).show();}case 3->new AlertDialog.Builder(this).setTitle("Disconnect podcasts?").setMessage("Playback and phone downloads stop. Private saved data remains scoped to this server.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(dd,w)->disconnect()).show();}}).show();}
    void disconnect(){generation++;if(player!=null)player.pause();if(future!=null){MediaController.releaseFuture(future);future=null;}player=null;stopService(new Intent(this,PodcastPlaybackService.class));PodcastLibrary.pause(this);new androidx.media3.exoplayer.scheduler.PlatformScheduler(this,43).cancel();PodcastApi.clear(this);api=null;library=null;clock=null;episodes.clear();connectScreen();}
    void say(String value){if(status!=null)status.setText(value);}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(future!=null)MediaController.releaseFuture(future);io.shutdown();super.onDestroy();}
}
