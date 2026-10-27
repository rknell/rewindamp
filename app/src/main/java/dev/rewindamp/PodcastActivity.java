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
    private TextView status,title,artist,queueLabel;
    private Button previous,next,playPause,speed,edit,orderButton,viewFilter;
    private boolean seeking,buildingQueue;
    private int rawOffset;
    private long downloadUpdated;
    private final java.util.Map<String,TextView> downloadLabels=new java.util.HashMap<>();
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
    Button icon(String symbol,String label,Runnable action){Retro.MetalButton button=new Retro.MetalButton(this,"").icon(symbol);button.setContentDescription(label);button.setTooltipText(label);button.setOnClickListener(v->action.run());return button;}
    void setLabel(TextView view,CharSequence value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    void rail(String value){root.addView(new Retro.Rail(this,value),new LinearLayout.LayoutParams(-1,dp(24)));}
    @Override public void onCreate(Bundle state){super.onCreate(state);api=PodcastApi.load(this);if(api==null)connectScreen();else{library=PodcastLibrary.get(this,api);String savedFeed=library.store.read("podcast-browser").optString("feed");feed=savedFeed.isBlank()?null:savedFeed;screen();connectPlayer();loadFeeds();}handler.post(ticker);}
    void base(){
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=column();root.setPadding(dp(8),dp(8),dp(8),dp(8));root.setBackground(Retro.bevel(Retro.BG,false));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){var padding=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());root.setPadding(dp(8)+padding.left,dp(8)+padding.top,dp(8)+padding.right,dp(8)+padding.bottom);}
            else root.setPadding(dp(8)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(8)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;
        });setContentView(root);
        LinearLayout header=row();header.addView(new Retro.Rail(this,"REWINDAMP / PODCASTS"),new LinearLayout.LayoutParams(0,dp(36),1));
        if(library!=null){header.addView(icon("sleep","Sleep timer",this::sleepTimer),new LinearLayout.LayoutParams(dp(44),dp(44)));header.addView(Retro.settingsButton(this,this::settings),new LinearLayout.LayoutParams(dp(44),dp(44)));}root.addView(header);
    }
    EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setTextColor(Retro.GREEN);e.setHintTextColor(Retro.MUTED);e.setTypeface(Retro.MONO);e.setTextSize(13);e.setSingleLine();e.setHint(hint);e.setBackground(Retro.bevel(Retro.LCD,true));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(secret?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_VARIATION_URI));if(secret)e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return e;}
    void connectScreen(){base();root.addView(text("PODCAST SERVER",24,Retro.GREEN));root.addView(text("Independent subscriptions, archive and listening history.\nUse HTTPS outside your trusted network.",12,Retro.MUTED));
        EditText server=input("https://podcasts.example.com",false),token=input("Owner password",true);root.addView(server,new LinearLayout.LayoutParams(-1,dp(56)));root.addView(token,new LinearLayout.LayoutParams(-1,dp(56)));
        status=text("",12,Retro.GOLD);Button connect=button("CONNECT",()->{});connect.setOnClickListener(v->{connect.setEnabled(false);String address=server.getText().toString(),secret=token.getText().toString();io.execute(()->{try{PodcastApi candidate=PodcastApi.login(address,secret);candidate.request("/api/feeds",null);PodcastApi.save(this,candidate);handler.post(()->{if(destroyed)return;token.setText("");api=candidate;library=PodcastLibrary.get(this,api);screen();connectPlayer();loadFeeds();});}catch(Exception e){handler.post(()->{connect.setEnabled(true);say("Connection failed. Check the address and password. Set your password on the website first.");});}});});root.addView(connect,new LinearLayout.LayoutParams(-1,dp(48)));root.addView(status,new LinearLayout.LayoutParams(-1,0,1));root.addView(Retro.navigationBar(this,true));}
    void screen(){
        base();LinearLayout lcd=column();lcd.setPadding(dp(12),dp(10),dp(12),dp(10));lcd.setBackground(Retro.bevel(Retro.LCD,true));
        title=text("READY WHEN YOU ARE",15,Retro.GREEN);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);title.setMarqueeRepeatLimit(-1);title.setSelected(true);lcd.addView(title);
        clock=new Retro.Digits(this);lcd.addView(clock,new LinearLayout.LayoutParams(-1,dp(45)));artist=text("PODCASTS · SPOKEN AUDIO",11,Retro.MUTED);artist.setSingleLine();artist.setEllipsize(android.text.TextUtils.TruncateAt.END);lcd.addView(artist);root.addView(lcd);
        seek=new SeekBar(this);seek.setMax(1000);seek.setThumb(Retro.thumb(this));seek.setProgressTintList(ColorStateList.valueOf(Retro.GOLD));seek.setContentDescription("Episode position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seeking=true;}public void onProgressChanged(SeekBar s,int value,boolean user){}public void onStopTrackingTouch(SeekBar s){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*s.getProgress()/1000);seeking=false;}});root.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout transport=row();previous=icon("previous","Previous episode",()->{if(player!=null&&player.hasPreviousMediaItem())player.seekToPreviousMediaItem();});weighted(transport,previous,48);
        weighted(transport,icon("back15","Jump back 15 seconds",()->{if(player!=null)player.seekBack();}),48);
        playPause=icon("play","Play",this::togglePlayback);playPause.setEnabled(false);weighted(transport,playPause,48);
        weighted(transport,icon("forward30","Jump forward 30 seconds",()->{if(player!=null)player.seekForward();}),48);
        next=icon("next","Next episode",()->{if(player!=null&&player.hasNextMediaItem())player.seekToNextMediaItem();});weighted(transport,next,48);
        speed=button("1×",this::speed);speed.setContentDescription("Playback speed");weighted(transport,speed,48);root.addView(transport);
        queueLabel=text("UP NEXT · Choose an episode",10,Retro.GOLD);queueLabel.setGravity(Gravity.CENTER_VERTICAL);queueLabel.setSingleLine();queueLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);queueLabel.setContentDescription("View podcast queue");queueLabel.setPadding(dp(8),0,dp(8),0);queueLabel.setBackground(Retro.bevel(Retro.PANEL,true));queueLabel.setOnClickListener(v->queue());root.addView(queueLabel,new LinearLayout.LayoutParams(-1,dp(36)));
        LinearLayout libraryHeader=row();libraryHeader.addView(new Retro.Rail(this,"PODCAST LIBRARY"),new LinearLayout.LayoutParams(0,dp(36),1));libraryHeader.addView(button("+ FIND PODCASTS",()->startActivityForResult(new Intent(this,PodcastDiscoveryActivity.class),77)),new LinearLayout.LayoutParams(dp(135),dp(44)));root.addView(libraryHeader);
        LinearLayout showRow=row();shows=new Spinner(this);shows.setContentDescription("Podcast show");showRow.addView(shows,new LinearLayout.LayoutParams(0,dp(48),1));edit=icon("edit","Edit subscription",this::pass);showRow.addView(edit,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(showRow);
        shows.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){feed=index<feedIds.size()?feedIds.get(index):null;try{library.store.write("podcast-browser",new JSONObject().put("feed",feed==null?"":feed));}catch(Exception ignored){}phone=false;inbox=!library.showPlayed();edit.setVisibility(feed==null?View.GONE:View.VISIBLE);load(false);}});
        LinearLayout filters=row();viewFilter=button("EPISODES ▾",()->new AlertDialog.Builder(this).setTitle("Episode view").setItems(new String[]{"Unplayed inbox","All available episodes","Phone downloads"},(d,i)->{phone=i==2;inbox=i==0;load(false);}).show());viewFilter.setContentDescription("Filter episodes");weighted(filters,viewFilter,44);
        orderButton=button("NEWEST FIRST",this::direction);orderButton.setContentDescription("Listening order");weighted(filters,orderButton,44);
        filters.addView(button("↻",()->load(false)),new LinearLayout.LayoutParams(dp(48),dp(44)));root.addView(filters);
        ScrollView scroll=new ScrollView(this);rows=column();rows.setBackground(Retro.bevel(Retro.LCD,true));scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));status=text("Choose an episode to build your queue",10,Retro.GOLD);status.setMaxLines(2);root.addView(status,new LinearLayout.LayoutParams(-1,dp(36)));root.addView(Retro.navigationBar(this,true));
    }
    void speed(){new AlertDialog.Builder(this).setTitle("Podcast speed").setSingleChoiceItems(new String[]{"0.75×","1×","1.25×","1.5×","2×"},-1,(d,i)->{if(player!=null)player.setPlaybackSpeed(new float[]{.75f,1f,1.25f,1.5f,2f}[i]);d.dismiss();}).setNegativeButton("Cancel",null).show();}
    void direction(){new AlertDialog.Builder(this).setTitle("Listening order · "+(feed==null?"All shows":"this show")).setSingleChoiceItems(new String[]{"Newest to oldest","Oldest to newest"},library.oldest(feed)?1:0,(d,i)->{library.preference("oldest:"+(feed==null?"all":feed),i==1);d.dismiss();load(false);say("Order saved · applies when you start an episode. Current queue retained.");}).setNegativeButton("Cancel",null).show();}
    void togglePlayback(){if(player==null)return;if(player.isPlaying()||player.getPlayWhenReady()){player.pause();return;}if(player.getMediaItemCount()==0){say("Choose an episode below to start listening");return;}if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);if(player.getPlaybackState()==Player.STATE_IDLE)player.prepare();player.play();}
    void queue(){if(player==null||player.getMediaItemCount()==0){say("Choose an episode to build your queue");return;}String[] labels=new String[player.getMediaItemCount()];for(int i=0;i<labels.length;i++)labels[i]=(i==player.getCurrentMediaItemIndex()?"▶ ":"")+player.getMediaItemAt(i).mediaMetadata.title;new AlertDialog.Builder(this).setTitle("Listening queue · "+labels.length+" episodes").setItems(labels,(d,i)->{player.seekTo(i,androidx.media3.common.C.TIME_UNSET);player.prepare();player.play();}).setNegativeButton("Close",null).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==77&&result==RESULT_OK&&library!=null){pendingFeedId=data==null?null:data.getStringExtra("feed_id");loadFeeds();say("Subscribed · the server will check the feed shortly");}}
    void connectPlayer(){
        future=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PodcastPlaybackService.class))).buildAsync();
        future.addListener(()->{try{if(destroyed)return;player=future.get();player.addListener(new Player.Listener(){@Override public void onEvents(Player p,Player.Events events){update();if(events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)||(events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)&&p.getPlaybackState()==Player.STATE_ENDED))load(false);}@Override public void onPlayerError(androidx.media3.common.PlaybackException error){say("Playback failed · check your connection or choose a phone download");}});update();}catch(Exception e){say("Could not connect to podcast playback");}},task->handler.post(task));
    }
    void update(){
        if(library!=null&&System.currentTimeMillis()-downloadUpdated>1500){downloadUpdated=System.currentTimeMillis();for(var entry:downloadLabels.entrySet()){String label=library.label(entry.getKey());if(!label.isBlank())setLabel(entry.getValue(),label);}}
        if(player==null||clock==null)return;long position=Math.max(0,player.getCurrentPosition());clock.setTime(Track.time(position));
        if(player.getCurrentMediaItem()!=null){var meta=player.getCurrentMediaItem().mediaMetadata;setLabel(title,meta.title);setLabel(artist,meta.artist+" · "+(player.getDuration()>0?"−"+Track.time(Math.max(0,player.getDuration()-position))+" remaining":"Duration unknown")+(library.sleepDescription().equals("off")?"":" · Sleep "+library.sleepDescription()));}
        long duration=player.getDuration();if(!seeking)seek.setProgress(duration>0?(int)(1000*position/duration):0);
        playPause.setEnabled(true);boolean playing=player.getPlayWhenReady();((Retro.MetalButton)playPause).icon(playing?"pause":"play");playPause.setTooltipText(playing?"Pause":"Play");
        previous.setEnabled(player.hasPreviousMediaItem());previous.setAlpha(previous.isEnabled()?1f:.4f);next.setEnabled(player.hasNextMediaItem());next.setAlpha(next.isEnabled()?1f:.4f);
        setLabel(speed,player.getPlaybackParameters().speed+"×");
        int index=player.getCurrentMediaItemIndex(),count=player.getMediaItemCount();String nextTitle=index+1<count?String.valueOf(player.getMediaItemAt(index+1).mediaMetadata.title):"End of queue";
        setLabel(queueLabel,count==0?"UP NEXT · Choose an episode":"QUEUE "+(index+1)+"/"+count+" · "+nextTitle);
    }
    void loadFeeds(){io.execute(()->{try{var feeds=library.feeds();List<String> labels=new ArrayList<>();List<String> ids=new ArrayList<>();labels.add("ALL SHOWS");ids.add(null);for(int i=0;i<feeds.length();i++){JSONObject f=feeds.getJSONObject(i);labels.add(f.getString("title"));ids.add(f.getString("id"));}handler.post(()->{if(destroyed)return;feedIds.clear();feedIds.addAll(ids);var adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);String selectedId=pendingFeedId!=null?pendingFeedId:feed;pendingFeedId=null;shows.setAdapter(adapter);int selected=ids.indexOf(selectedId);shows.setSelection(Math.max(0,selected));});}catch(Exception e){handler.post(()->{if(destroyed)return;if(e.getMessage()!=null&&e.getMessage().contains("HTTP 401")){disconnect();say("Sign in again with your owner password. Phone downloads are retained.");}else say("Server unavailable · phone downloads remain playable");});}});}
    boolean visible(JSONObject episode){return library.showPlayed()||!List.of("played","dismissed").contains(library.episode(episode.optString("id")).optString("state"));}
    void load(boolean append){
        if(loading&&append)return;int request=++generation;loading=true;String wanted=feed;boolean wantInbox=inbox,wantPhone=phone,oldest=library.oldest(feed),hidePlayed=!library.showPlayed();int offset=append?rawOffset:0;
        setLabel(viewFilter,wantPhone?"PHONE ▾":wantInbox?"INBOX ▾":"ALL EPISODES ▾");setLabel(orderButton,oldest?"OLDEST FIRST":"NEWEST FIRST");if(!append){rows.removeAllViews();rows.addView(text("LOADING EPISODES…",12,Retro.MUTED));}say("Checking episodes…");
        io.execute(()->{try{library.sync();List<JSONObject> list=wantPhone?library.downloaded():library.episodes(wanted,wantInbox,offset,oldest,hidePlayed);int received=list.size();if(wantPhone){list.removeIf(e->(wanted!=null&&!wanted.equals(e.optString("feed_id")))||(!library.showPlayed()&&!visible(e)));list.sort((a,b)->{int order=Double.compare(a.optDouble("published"),b.optDouble("published"));return order==0?a.optString("id").compareTo(b.optString("id")):(oldest?order:-order);});}
            List<JSONObject> result=list;handler.post(()->{if(destroyed||request!=generation)return;loading=false;if(!append)episodes.clear();rawOffset=offset+received;episodes.addAll(result);more=!wantPhone&&received==100;render();say((wantPhone?"PHONE DOWNLOADS":wantInbox?"UNPLAYED INBOX":"ALL AVAILABLE EPISODES")+" · "+episodes.stream().filter(this::visible).count()+" EPISODES"+(hidePlayed?" · played hidden":" · played shown"));});
        }catch(Exception e){handler.post(()->{if(destroyed||request!=generation)return;loading=false;rows.removeAllViews();rows.addView(text("COULD NOT LOAD EPISODES\nCheck your connection, or open phone downloads.",12,Retro.MUTED));rows.addView(button("RETRY",()->load(false)));rows.addView(button("PHONE DOWNLOADS",()->{phone=true;load(false);}));say("Server unavailable · phone downloads remain playable");});}});
    }
    void viewAll(){phone=false;inbox=false;load(false);}
    void render(){
        rows.removeAllViews();downloadLabels.clear();boolean empty=episodes.stream().noneMatch(this::visible);
        if(empty){LinearLayout message=column();message.setPadding(dp(14),dp(16),dp(14),dp(16));message.addView(text(phone?"NO PHONE DOWNLOADS":inbox?"YOU’RE ALL CAUGHT UP":"NO EPISODES YET",16,Retro.GREEN));message.addView(text(phone?"Download an episode to keep a copy on this phone.":inbox?"New subscriptions may be future-only. Browse the archive to download an older episode.":"The server may still be checking this feed. Refresh shortly, or show played episodes in Settings.",12,Retro.MUTED));message.addView(button("View all podcasts",this::viewAll),new LinearLayout.LayoutParams(-1,dp(48)));rows.addView(message);}
        for(JSONObject episode:episodes){if(!visible(episode))continue;String id=episode.optString("id");JSONObject local=library.episode(id);LinearLayout item=column();item.setPadding(dp(8),dp(8),dp(8),dp(8));TextView label=text(episode.optString("title"),14,Retro.GREEN);label.setMaxLines(2);item.addView(label);if(feed==null)item.addView(text(episode.optString("show_title"),11,Retro.GOLD));
            String date=episode.optDouble("published")>0?new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(new java.util.Date((long)(episode.optDouble("published")*1000))):"UNDATED";
            item.addView(text(date+" · "+local.optString("state","unplayed").replace('_',' ')+" · "+Track.time((long)(local.optDouble("position")*1000))+" / "+Track.time((long)(episode.optDouble("duration")*1000)),10,Retro.MUTED));
            String saved=library.label(id);TextView downloadStatus=text(saved.isBlank()?(episode.optBoolean("downloaded")?"SERVER SAVED":episode.optBoolean("eligible")?"SERVER DOWNLOAD PENDING":"AVAILABLE TO DOWNLOAD"):saved,10,Retro.GOLD);item.addView(downloadStatus);downloadLabels.put(id,downloadStatus);
            LinearLayout actions=row();boolean playable=episode.optBoolean("downloaded")||library.complete(id);Button start=button(playable?(local.optDouble("position")>0&&!local.optString("state").equals("played")?"RESUME":"PLAY"):episode.optBoolean("eligible")?"PENDING":"DOWNLOAD",()->{if(playable)play(episode);else request(()->{api.request("/api/episodes/"+id+"/download",new JSONObject());handler.post(()->load(false));return "Server download queued";});});start.setEnabled(playable||!episode.optBoolean("eligible"));actions.addView(start,new LinearLayout.LayoutParams(0,dp(44),1));Button options=button("⋯",()->episodeMenu(episode));options.setContentDescription("Episode actions: "+episode.optString("title"));actions.addView(options,new LinearLayout.LayoutParams(dp(48),dp(44)));item.addView(actions);rows.addView(item);
        }if(more)rows.addView(button("MORE EPISODES",()->load(true)));
    }
    void play(JSONObject episode){
        if(player==null||buildingQueue)return;String id=episode.optString("id");if(!episode.optBoolean("downloaded")&&!library.complete(id)){say("Wait for the server download, or choose Download on server");return;}
        buildingQueue=true;String selectedFeed=feed;boolean selectedPhone=phone,selectedInbox=inbox,oldest=library.oldest(feed),includePlayed=library.showPlayed();say("Building listening queue…");
        io.execute(()->{try{
            List<JSONObject> candidates=new ArrayList<>();if(selectedPhone)candidates.addAll(library.downloaded());else{int offset=0;while(true){List<JSONObject> page=library.episodes(selectedFeed,selectedInbox,offset,oldest,!includePlayed);candidates.addAll(page);if(page.size()<100)break;offset+=100;if(destroyed)return;}}
            // Snapshot the selected browser context. Later browsing does not alter playback.
            candidates.removeIf(e->(selectedFeed!=null&&!selectedFeed.equals(e.optString("feed_id")))||(!e.optBoolean("downloaded")&&!library.complete(e.optString("id")))||(!e.optString("id").equals(id)&&!includePlayed&&List.of("played","dismissed").contains(library.episode(e.optString("id")).optString("state"))));
            if(candidates.stream().noneMatch(e->e.optString("id").equals(id)))candidates.add(episode);
            candidates.sort((a,b)->{int order=Double.compare(a.optDouble("published"),b.optDouble("published"));return order==0?a.optString("id").compareTo(b.optString("id")):(oldest?order:-order);});
            List<androidx.media3.common.MediaItem> queue=new ArrayList<>();int startIndex=0;for(JSONObject e:candidates){if(e.optString("id").equals(id))startIndex=queue.size();queue.add(library.media(e));}int index=startIndex;
            handler.post(()->{buildingQueue=false;if(destroyed||player==null)return;JSONObject local=library.episode(id);long start=local.optString("state").equals("played")?0:(long)(local.optDouble("position")*1000);player.setMediaItems(queue,index,start);player.prepare();player.play();update();say("Queue ready · "+(selectedFeed==null?"all shows":"selected show")+" · "+(oldest?"oldest first":"newest first")+" · saved episodes only");});
        }catch(Exception e){handler.post(()->{buildingQueue=false;if(destroyed)return;say("Could not build queue · retry when connected");});}});
    }
    void episodeMenu(JSONObject episode){new AlertDialog.Builder(this).setTitle(episode.optString("title")).setItems(new String[]{"Resume episode","Mark played","Dismiss from inbox","Mark unplayed / reset position",library.download(episode.optString("id"))!=null&&library.download(episode.optString("id")).state==androidx.media3.exoplayer.offline.Download.STATE_FAILED?"Retry phone download":"Download copy to phone","Remove copy from phone","Download on server"},(d,i)->{switch(i){case 0->play(episode);case 1->mark(episode,"played");case 2->mark(episode,"dismissed");case 3->mark(episode,"reset");case 4->{try{if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},12);library.download(episode);say("Phone download queued · open PHONE to check");}catch(Exception e){say("Wait for the server to finish downloading this episode first");}}case 5->new AlertDialog.Builder(this).setTitle("Remove phone copy?").setMessage("Listening history and the server archive are retained.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(dd,w)->{library.remove(episode.optString("id"));say("Phone copy removal queued");}).show();case 6->request(()->{api.request("/api/episodes/"+episode.getString("id")+"/download",new JSONObject());return "Server download queued";});}}).show();}
    void mark(JSONObject episode,String action){String id=episode.optString("id");if(id.isBlank())return;long position=(long)(library.episode(id).optDouble("position")*1000);if(player!=null&&player.getCurrentMediaItem()!=null&&player.getCurrentMediaItem().mediaId.equals(id)){player.pause();position=Math.max(0,player.getCurrentPosition());if(action.equals("reset"))player.seekTo(0);}library.event(id,position,action);io.execute(()->library.sync());load(false);}
    void pass(){if(feed==null){say("Choose a show before changing its pass");return;}String id=feed;request(()->{var all=library.feeds();JSONObject found=null;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))found=all.getJSONObject(i);JSONObject target=found;if(target==null)return "Show not found";handler.post(()->new AlertDialog.Builder(this).setTitle("Download episodes automatically").setItems(new String[]{"Future episodes only","Last 12 months","Last two years","This year (from January 1)","All available episodes","Manual downloads only"},(d,which)->request(()->{String mode=switch(which){case 1,2->"years";case 3->"since";case 4->"all";case 5->"manual";default->"future";};JSONObject body=new JSONObject().put("title",target.getString("title")).put("url",target.getString("url")).put("mode",mode).put("years",which==2?2:1);if(which==3){java.util.Calendar start=java.util.Calendar.getInstance();start.set(java.util.Calendar.DAY_OF_YEAR,1);start.set(java.util.Calendar.HOUR_OF_DAY,0);start.set(java.util.Calendar.MINUTE,0);start.set(java.util.Calendar.SECOND,0);start.set(java.util.Calendar.MILLISECOND,0);body.put("since",start.getTimeInMillis()/1000);}api.request("/api/feeds",body);return "Download rule saved · archive and history retained";})).show());return "Choose which episodes to download automatically";});}
    interface Job{String run()throws Exception;}
    void request(Job task){io.execute(()->{try{String message=task.run();handler.post(()->{if(!destroyed)say(message);});}catch(Exception e){handler.post(()->say("Podcast server request failed · retry when connected"));}});}
    void sleepTimer(){
        new AlertDialog.Builder(this).setTitle("Sleep timer · "+library.sleepDescription()).setItems(new String[]{"Off","15 minutes","30 minutes","45 minutes","60 minutes","End of this episode"},(d,i)->{
            if(i==0)library.clearSleep();else if(i==5){if(player==null||player.getCurrentMediaItem()==null){say("Start an episode before setting its end timer");return;}library.sleep(0,true,player.getCurrentMediaItem().mediaId);}else library.sleep(System.currentTimeMillis()+new int[]{0,15,30,45,60}[i]*60000L,false,"");say("Sleep timer · "+library.sleepDescription());update();
        }).setNegativeButton("Cancel",null).show();
    }
    void offlineSettings(){
        boolean paused=library.store.read("podcast-preferences").optBoolean("downloads_paused");
        new AlertDialog.Builder(this).setTitle("Offline downloads").setItems(new String[]{library.offlineSummary(),"View phone downloads / retry failed copies","Download next 3 unplayed episodes in this view","Auto-download next · "+library.autoDownloads(),"Wi-Fi only · "+(library.wifiOnly()?"on":"off"),paused?"Resume downloads":"Pause downloads","Remove completed phone copies"},(d,i)->{
            switch(i){case 0->offlineSettings();case 1->{phone=true;load(false);}case 2->downloadNext();case 3->new AlertDialog.Builder(this).setTitle("Keep upcoming queued episodes offline").setItems(new String[]{"Off","Next episode","Next 3 episodes","Next 5 episodes"},(dd,index)->{int count=new int[]{0,1,3,5}[index];library.autoDownloads(count);if(count>0&&android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},12);say(count==0?"Automatic phone downloads off":"Next "+count+" queued episodes will download · "+(library.wifiOnly()?"Wi-Fi only":"any network"));}).setNegativeButton("Cancel",null).show();case 4->{library.wifiOnly(!library.wifiOnly());offlineSettings();}case 5->{library.pauseDownloads(!paused);say(paused?"Phone downloads resumed":"Phone downloads paused");}case 6->new AlertDialog.Builder(this).setTitle("Remove completed phone copies?").setMessage("Only phone audio copies are removed. Server archives, your queue and listening history are kept.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(dd,w)->{library.autoDownloads(0);for(JSONObject e:library.downloaded())if(library.complete(e.optString("id")))library.remove(e.optString("id"));say("Phone copies being removed · automatic downloads turned off");}).show();}
        }).setNegativeButton("Close",null).show();
    }
    void downloadNext(){
        String selectedFeed=feed;boolean oldest=library.oldest(feed);request(()->{int queued=0,offset=0;while(queued<3){List<JSONObject> page=library.episodes(selectedFeed,false,offset,oldest,true);for(JSONObject e:page){if(!e.optBoolean("downloaded")||library.download(e.optString("id"))!=null||!visible(e))continue;library.download(e);if(++queued==3)break;}if(page.size()<100)break;offset+=100;}return queued+" phone downloads queued · "+(library.wifiOnly()?"Wi-Fi only":"any network");});
    }
    void settings(){new AlertDialog.Builder(this).setTitle("Podcast settings").setItems(new String[]{"Sync listening history","Refresh selected show / all shows","Add RSS feed","Disconnect podcast server",library.showPlayed()?"Hide played and dismissed episodes":"Show played and dismissed episodes","Offline downloads","Sleep timer · "+library.sleepDescription()},(d,i)->{switch(i){case 5->offlineSettings();case 6->sleepTimer();case 4->{library.preference("show_played",!library.showPlayed());if(library.showPlayed())inbox=false;load(false);}case 0->request(()->{library.sync();return "Sync attempted · pending changes retry while playback service is running";});case 1->request(()->{if(feed!=null)api.request("/api/feeds/"+feed+"/refresh",new JSONObject());else{var feeds=library.feeds();for(int n=0;n<feeds.length();n++)api.request("/api/feeds/"+feeds.getJSONObject(n).getString("id")+"/refresh",new JSONObject());}return "Feed refresh queued · refresh this view shortly";});case 2->{EditText url=input("https://example.com/podcast.rss",false);new AlertDialog.Builder(this).setTitle("Add RSS feed · future episodes only").setView(url).setNegativeButton("Cancel",null).setPositiveButton("Subscribe",(dd,w)->{String address=url.getText().toString().trim();request(()->{api.request("/api/feeds",new JSONObject().put("url",address).put("mode","future").put("only_if_new",true));handler.post(this::loadFeeds);return "Subscribed · initial feed establishes the baseline";});}).show();}case 3->new AlertDialog.Builder(this).setTitle("Disconnect podcasts?").setMessage("Playback and phone downloads stop. Private saved data remains scoped to this server.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(dd,w)->disconnect()).show();}}).show();}
    void disconnect(){generation++;if(player!=null)player.pause();if(future!=null){MediaController.releaseFuture(future);future=null;}player=null;stopService(new Intent(this,PodcastPlaybackService.class));PodcastLibrary.pause(this);new androidx.media3.exoplayer.scheduler.PlatformScheduler(this,43).cancel();PodcastApi.clear(this);api=null;library=null;clock=null;episodes.clear();connectScreen();}
    void say(String value){if(status!=null)status.setText(value);}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(future!=null)MediaController.releaseFuture(future);io.shutdown();super.onDestroy();}
}
