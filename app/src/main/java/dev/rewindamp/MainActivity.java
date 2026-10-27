package dev.rewindamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final String[] SECTIONS={"Tracks","Albums","Artists","Playlists","Favourites","Offline","History","Podcasts"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(),searchIo=Executors.newFixedThreadPool(2);
    private final ArtworkLoader artwork=new ArtworkLoader();
    private final List<Track> shown=new ArrayList<>(),pendingQueue=new ArrayList<>();
    private final Set<Integer> selected=new TreeSet<>();
    private final Map<String,TextView> downloadLabels=new HashMap<>();
    private JellyfinApi api;
    private OfflineLibrary offline;
    private LibraryStore store;
    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private java.util.concurrent.Future<?> browseTask;
    private LinearLayout root,tools,equalizer,eqControls;
    private ListView resultScroll;
    private BaseAdapter resultAdapter;
    private final List<Integer> visibleRows=new ArrayList<>();
    private boolean loadingPage=false,pageFailed=false;
    private TextView title,artist,status,count,audioInfo;
    private Retro.Digits clock;
    private Retro.Spectrum spectrum;
    private Button play,previous,next,shuffle,repeat,queueButton,sectionButton,undoButton,eqButton;
    private ImageView cover;
    private SeekBar seek,volume;
    private EditText search;
    private boolean searchScreen=false;
    private BrowseState previousBrowse;
    private List<Track> previousBrowseRows;
    private boolean previousBrowseMore;
    private final Deque<BrowsePage> browseHistory=new ArrayDeque<>();
    private int restoreScroll=-1,restoreTop=0;
    private record BrowseState(String section,String query,String parent,String parentType,String parentName,int scroll,int top){}
    private record BrowsePage(BrowseState state,List<Track> rows,boolean more){}
    private final Runnable searchDebounce=()->{if(searchScreen&&!this.destroyed)searchNow();};
    private String section="Tracks",query="",parent=null,parentName="",parentType="",artId="";
    private int generation=0;
    private boolean demo=false,busy=false,destroyed=false,seeking=false,selecting=false,eqExpanded=true,morePages=false,remainingTime=false;
    private Runnable undo;
    private long undoTime;
    private final Runnable ticker=new Runnable(){@Override public void run(){if(!destroyed){updatePlayer();updateDownloads();handler.postDelayed(this,500);}}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);DspSettings.load(this);
        if(state!=null){section=state.getString("section","Tracks");query=state.getString("query","");parent=state.getString("parent");parentType=state.getString("parentType","");parentName=state.getString("parentName","");searchScreen=state.getBoolean("searchScreen");
            restoreScroll=state.getInt("scroll",0);restoreTop=state.getInt("scrollTop",0);
            ArrayList<Bundle> history=state.getParcelableArrayList("browseHistory");if(history!=null)for(Bundle page:history)browseHistory.addLast(new BrowsePage(readBrowse(page),null,false));
            if(searchScreen)previousBrowse=new BrowseState(state.getString("browseSection","Tracks"),state.getString("browseQuery",""),state.getString("browseParent"),state.getString("browseType",""),state.getString("browseName",""),state.getInt("browseScroll"),state.getInt("browseTop"));}
        remainingTime=getPreferences(0).getBoolean("remainingTime",false);eqExpanded=getPreferences(0).getBoolean("eqExpanded",true);api=SessionStore.load(this);
        if(api==null)loginScreen();else{offline=OfflineLibrary.get(this,api);store=offline.store;if(searchScreen)searchLayout(false);else mainScreen();connectPlayer();load(false);}
        handleVoiceSearch(getIntent());handler.post(ticker);
    }
    private void handleVoiceSearch(Intent intent){
        if(intent!=null&&android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction())){
            String term=intent.getStringExtra(android.app.SearchManager.QUERY);if(term!=null&&!term.isBlank()&&api!=null){openSearch(term);say("Search results · tap to add to queue");}
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleVoiceSearch(intent);}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("section",section);state.putString("query",query);state.putString("parent",parent);state.putString("parentType",parentType);state.putString("parentName",parentName);state.putBoolean("searchScreen",searchScreen);
        BrowseState current=captureBrowse();state.putInt("scroll",current.scroll());state.putInt("scrollTop",current.top());
        ArrayList<Bundle> history=new ArrayList<>();for(BrowsePage page:browseHistory)history.add(saveBrowse(page.state()));state.putParcelableArrayList("browseHistory",history);
        if(previousBrowse!=null){state.putString("browseSection",previousBrowse.section());state.putString("browseQuery",previousBrowse.query());state.putString("browseParent",previousBrowse.parent());state.putString("browseType",previousBrowse.parentType());state.putString("browseName",previousBrowse.parentName());state.putInt("browseScroll",previousBrowse.scroll());state.putInt("browseTop",previousBrowse.top());}}
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Retro.MONO);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    void setLabel(TextView view,String value){if(!TextUtils.equals(view.getText(),value))view.setText(value);}
    Button button(String s,Runnable action){Retro.MetalButton b=new Retro.MetalButton(this,s);b.setOnClickListener(v->action.run());return b;}
    Button transport(String icon,Runnable action){Retro.MetalButton b=new Retro.MetalButton(this,"").icon(icon);b.setOnClickListener(v->action.run());return b;}
    void weighted(LinearLayout row,View v,int height){row.addView(v,new LinearLayout.LayoutParams(0,dp(height),1));}
    void gap(LinearLayout box,int height){box.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    void rail(LinearLayout box,String name){box.addView(new Retro.Rail(this,name),new LinearLayout.LayoutParams(-1,dp(22)));}
    void panel(LinearLayout box){box.setBackground(Retro.bevel(Retro.PANEL,false));box.setPadding(dp(5),dp(3),dp(5),dp(5));}
    void base(){
        if(spectrum!=null){spectrum.setPlaybackActive(false);spectrum=null;}
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root=column();root.setBackgroundColor(Retro.BG);root.setPadding(dp(8),dp(6),dp(8),dp(6));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){var padding=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());root.setPadding(dp(8)+padding.left,dp(6)+padding.top,dp(8)+padding.right,dp(6)+padding.bottom);}
            else root.setPadding(dp(8)+insets.getSystemWindowInsetLeft(),dp(6)+insets.getSystemWindowInsetTop(),dp(8)+insets.getSystemWindowInsetRight(),dp(6)+insets.getSystemWindowInsetBottom());return insets;});setContentView(root);
    }
    EditText field(String hint,boolean password){EditText e=new EditText(this);e.setTextColor(Retro.GREEN);e.setHintTextColor(Retro.MUTED);e.setTextSize(13);e.setSingleLine(true);e.setTypeface(Retro.MONO);e.setPadding(dp(9),dp(5),dp(9),dp(5));e.setBackground(Retro.bevel(Retro.LCD,true));e.setHint(hint);e.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);if(password)e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return e;}
    void loginScreen(){
        searchScreen=false;previousBrowse=null;previousBrowseRows=null;browseHistory.clear();base();ScrollView scroll=new ScrollView(this);LinearLayout box=column();scroll.addView(box);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));root.addView(Retro.navigationBar(this,false));rail(box,"REWINDAMP / CONNECT");gap(box,24);
        box.addView(text("REWINDAMP",34,Retro.GREEN));box.addView(text("YOUR MUSIC. OLD SCHOOL.",12,Retro.MUTED));gap(box,24);
        LinearLayout form=column();panel(form);form.setPadding(dp(16),dp(12),dp(16),dp(12));box.addView(form);rail(form,"JELLYFIN SERVER");gap(form,12);
        EditText server=field("https://jellyfin.example.com",false);server.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);form.addView(server,new LinearLayout.LayoutParams(-1,dp(52)));gap(form,12);
        EditText user=field("Username",false);form.addView(user,new LinearLayout.LayoutParams(-1,dp(52)));gap(form,12);EditText pass=field("Password",true);form.addView(pass,new LinearLayout.LayoutParams(-1,dp(52)));gap(form,14);
        TextView error=text("",12,0xFFE9BD67);Button connect=button("CONNECT TO JELLYFIN",()->{});
        connect.setOnClickListener(v->{String address=server.getText().toString(),username=user.getText().toString(),password=pass.getText().toString();if(address.isBlank()||username.isBlank()){error.setText("Enter a server URL and username.");return;}
            connect.setEnabled(false);error.setText("Connecting…");io.execute(()->{try{JellyfinApi signed=JellyfinApi.login(address,username,password);SessionStore.save(this,signed);handler.post(()->{if(destroyed)return;pass.setText("");api=signed;offline=OfflineLibrary.get(this,api);store=offline.store;demo=false;mainScreen();connectPlayer();load(false);});}catch(Exception ex){handler.post(()->{if(destroyed)return;connect.setEnabled(true);error.setText(message(ex));});}});
        });
        form.addView(connect,new LinearLayout.LayoutParams(-1,dp(48)));gap(form,10);form.addView(error);gap(box,18);
        box.addView(button("PODCASTS · CONNECT PODCAST SERVER",()->startActivity(new Intent(this,PodcastActivity.class))),new LinearLayout.LayoutParams(-1,dp(48)));gap(box,12);
        box.addView(button("EXPLORE THE DEMO",()->{demo=true;mainScreen();load(false);}),new LinearLayout.LayoutParams(-1,dp(48)));gap(box,18);
        box.addView(text("Use your own Jellyfin account.\nUse HTTPS outside your trusted home network.\n\nA little 1997, wherever you go.",12,Retro.MUTED));
    }
    void mainScreen(){
        base();artId="";
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout deckHost=root,libraryHost=root;
        if(landscape){LinearLayout split=row();split.setGravity(Gravity.TOP);root.addView(split,new LinearLayout.LayoutParams(-1,0,1));ScrollView left=new ScrollView(this);deckHost=column();left.addView(deckHost);split.addView(left,new LinearLayout.LayoutParams(0,-1,1));libraryHost=column();LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-1,1);right.leftMargin=dp(6);split.addView(libraryHost,right);}
        LinearLayout deck=column();panel(deck);deckHost.addView(deck);rail(deck,"REWINDAMP");
        LinearLayout lcd=row();lcd.setPadding(dp(6),dp(4),dp(6),dp(4));lcd.setBackground(Retro.bevel(Retro.LCD,true));deck.addView(lcd,new LinearLayout.LayoutParams(-1,dp(86)));
        LinearLayout meters=column();clock=new Retro.Digits(this);clock.setFocusable(true);clock.setOnClickListener(v->{remainingTime=!remainingTime;getPreferences(0).edit().putBoolean("remainingTime",remainingTime).apply();updateClock();});meters.addView(clock,new LinearLayout.LayoutParams(-1,dp(42)));spectrum=new Retro.Spectrum(this);meters.addView(spectrum,new LinearLayout.LayoutParams(-1,dp(29)));lcd.addView(meters,new LinearLayout.LayoutParams(dp(122),-1));
        LinearLayout info=column();info.setPadding(dp(8),0,0,0);lcd.addView(info,new LinearLayout.LayoutParams(0,-1,1));title=text("READY WHEN YOU ARE",12,Retro.GREEN);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.MARQUEE);title.setMarqueeRepeatLimit(-1);title.setSelected(true);info.addView(title,new LinearLayout.LayoutParams(-1,dp(24)));
        LinearLayout details=row();cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setImageResource(R.drawable.ic_rewindamp);cover.setContentDescription("Current album cover");details.addView(cover,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout labels=column();labels.setPadding(dp(7),0,0,0);artist=text("JELLYFIN MUSIC",10,Retro.MUTED);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);labels.addView(artist);audioInfo=text("STEREO",9,Retro.GREEN);labels.addView(audioInfo);details.addView(labels,new LinearLayout.LayoutParams(0,-2,1));info.addView(details);
        LinearLayout switches=row();volume=new SeekBar(this);volume.setMax(100);volume.setProgress(100);volume.setContentDescription("Playback volume");volume.setThumb(Retro.thumb(this));volume.setProgressTintList(ColorStateList.valueOf(0xFFC88737));switches.addView(volume,new LinearLayout.LayoutParams(0,dp(30),1));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int progress,boolean user){if(user&&player!=null)player.setVolume(progress/100f);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        eqButton=button("EQ",()->{eqExpanded=!eqExpanded;equalizer.setVisibility(eqExpanded?View.VISIBLE:View.GONE);getPreferences(0).edit().putBoolean("eqExpanded",eqExpanded).apply();});switches.addView(eqButton,new LinearLayout.LayoutParams(dp(42),dp(34)));
        switches.addView(button("PL",()->navigate("Queue")),new LinearLayout.LayoutParams(dp(42),dp(34)));switches.addView(Retro.settingsButton(this,this::settings),new LinearLayout.LayoutParams(dp(42),dp(34)));deck.addView(switches);
        seek=new SeekBar(this);seek.setMax(1000);seek.setThumb(Retro.thumb(this));seek.setProgressTintList(ColorStateList.valueOf(Retro.GOLD));seek.setContentDescription("Track position");deck.addView(seek,new LinearLayout.LayoutParams(-1,dp(24)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seeking=true;}public void onProgressChanged(SeekBar s,int value,boolean user){}public void onStopTrackingTouch(SeekBar s){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*s.getProgress()/1000);seeking=false;}});
        LinearLayout controls=row();previous=transport("previous",()->{if(player!=null&&player.hasPreviousMediaItem())player.seekToPreviousMediaItem();});weighted(controls,previous,44);
        play=transport("play",this::resume);weighted(controls,play,44);weighted(controls,transport("pause",()->{if(player!=null)player.pause();}),44);
        weighted(controls,transport("stop",()->{if(player!=null){player.pause();player.seekTo(0);}}),44);next=transport("next",()->{if(player!=null&&player.hasNextMediaItem())player.seekToNextMediaItem();});weighted(controls,next,44);
        weighted(controls,transport("eject",this::chooseSection),44);shuffle=button("SHUF",()->{if(player!=null)player.setShuffleModeEnabled(!player.getShuffleModeEnabled());});controls.addView(shuffle,new LinearLayout.LayoutParams(dp(49),dp(44)));
        repeat=button("REP",()->{if(player!=null)player.setRepeatMode((player.getRepeatMode()+1)%3);});controls.addView(repeat,new LinearLayout.LayoutParams(dp(42),dp(44)));deck.addView(controls);gap(deckHost,5);
        equalizer=column();panel(equalizer);deckHost.addView(equalizer);rail(equalizer,"REWINDAMP EQUALIZER");buildEqualizer();equalizer.setVisibility(eqExpanded?View.VISIBLE:View.GONE);gap(deckHost,5);
        LinearLayout browser=column();panel(browser);libraryHost.addView(browser,new LinearLayout.LayoutParams(-1,0,1));rail(browser,"REWINDAMP PLAYLIST");
        LinearLayout navigation=row();sectionButton=button(section.toUpperCase(Locale.ROOT)+" ▾",this::chooseSection);navigation.addView(sectionButton,new LinearLayout.LayoutParams(0,dp(42),1));
        queueButton=button("QUEUE",()->navigate("Queue"));navigation.addView(queueButton,new LinearLayout.LayoutParams(dp(96),dp(42)));navigation.addView(button("SEL",()->{selecting=!selecting;selected.clear();render(morePages);}),new LinearLayout.LayoutParams(dp(44),dp(42)));browser.addView(navigation);
        LinearLayout find=row();search=field("Search music",false);search.setText(query);find.addView(search,new LinearLayout.LayoutParams(0,dp(40),1));find.addView(button("GO",this::searchNow),new LinearLayout.LayoutParams(dp(44),dp(40)));search.setFocusable(false);search.setCursorVisible(false);search.setOnClickListener(v->openSearch(""));browser.addView(find);
        count=text("",10,Retro.MUTED);count.setSingleLine(true);count.setEllipsize(TextUtils.TruncateAt.END);browser.addView(count,new LinearLayout.LayoutParams(-1,dp(22)));tools=row();browser.addView(tools,new LinearLayout.LayoutParams(-1,dp(40)));
        buildResults();browser.addView(resultScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row();status=text(demo?"DEMO · SAMPLE LIBRARY":"READY · TAP TRACKS TO QUEUE",10,Retro.MUTED);status.setMaxLines(2);footer.addView(status,new LinearLayout.LayoutParams(0,dp(42),1));undoButton=button("UNDO",this::doUndo);footer.addView(undoButton,new LinearLayout.LayoutParams(dp(54),dp(42)));libraryHost.addView(footer);root.addView(Retro.navigationBar(this,false));updatePlayer();
    }
    void buildEqualizer(){
        if(eqControls!=null&&eqControls.getParent()!=null)((ViewGroup)eqControls.getParent()).removeView(eqControls);
        eqControls=column();equalizer.addView(eqControls);DspSettings.Values settings=DspSettings.get();LinearLayout top=row();
        Button enabled=button(settings.enabled()?"▣ ON":"□ OFF",()->{DspSettings.Values s=DspSettings.get();DspSettings.set(this,!s.enabled(),s.preamp(),s.gains());buildEqualizer();});top.addView(enabled,new LinearLayout.LayoutParams(dp(64),dp(32)));
        TextView scale=text("+12dB  ━━━  -12dB",9,Retro.GOLD);scale.setGravity(Gravity.CENTER);top.addView(scale,new LinearLayout.LayoutParams(0,dp(28),1));top.addView(button("PRESETS",()->new AlertDialog.Builder(this).setTitle("Equalizer presets").setItems(new String[]{"Flat","Rock","Bass","Vocal","Electronic"},(d,i)->{DspSettings.preset(this,new String[]{"Flat","Rock","Bass","Vocal","Electronic"}[i]);buildEqualizer();}).show()),new LinearLayout.LayoutParams(dp(68),dp(32)));eqControls.addView(top);
        LinearLayout bands=row();bands.setGravity(Gravity.TOP);eqControls.addView(bands,new LinearLayout.LayoutParams(-1,dp(94)));
        weighted(bands,new Retro.BandSlider(this,"PRE",settings.preamp(),value->{DspSettings.Values s=DspSettings.get();DspSettings.set(this,s.enabled(),value,s.gains());}),94);
        for(int i=0;i<10;i++){int band=i;weighted(bands,new Retro.BandSlider(this,DspSettings.LABELS[i],settings.gains()[i],value->{DspSettings.Values s=DspSettings.get();float[] gains=s.gains().clone();gains[band]=value;DspSettings.set(this,s.enabled(),s.preamp(),gains);}),94);}
    }
    void chooseSection(){new AlertDialog.Builder(this).setTitle("Music library").setItems(SECTIONS,(d,i)->{if(SECTIONS[i].equals("Podcasts"))startActivity(new Intent(this,PodcastActivity.class));else navigate(SECTIONS[i]);}).setNegativeButton("Cancel",null).show();}
    BrowseState captureBrowse(){
        int scroll=restoreScroll>=0?restoreScroll:resultScroll==null?0:resultScroll.getFirstVisiblePosition();
        int top=restoreScroll>=0?restoreTop:resultScroll==null||resultScroll.getChildCount()==0?0:resultScroll.getChildAt(0).getTop()-resultScroll.getPaddingTop();
        return new BrowseState(section,query,parent,parentType,parentName,scroll,top);
    }
    BrowsePage capturePage(){return new BrowsePage(captureBrowse(),loadingPage&&shown.isEmpty()?null:new ArrayList<>(shown),morePages);}
    Bundle saveBrowse(BrowseState page){Bundle saved=new Bundle();saved.putString("section",page.section());saved.putString("query",page.query());saved.putString("parent",page.parent());saved.putString("type",page.parentType());saved.putString("name",page.parentName());saved.putInt("scroll",page.scroll());saved.putInt("top",page.top());return saved;}
    BrowseState readBrowse(Bundle saved){return new BrowseState(saved.getString("section","Tracks"),saved.getString("query",""),saved.getString("parent"),saved.getString("type",""),saved.getString("name",""),saved.getInt("scroll"),saved.getInt("top"));}
    void restorePage(BrowsePage page){
        generation++;if(browseTask!=null)browseTask.cancel(true);loadingPage=false;pageFailed=false;selected.clear();selecting=false;
        BrowseState state=page.state();section=state.section();query=state.query();parent=state.parent();parentType=state.parentType();parentName=state.parentName();restoreScroll=state.scroll();restoreTop=state.top();search.setText(query);
        if(queue()||section.equals("Offline")||page.rows()==null)load(false);else{shown.clear();shown.addAll(page.rows());render(page.more());say("TAP TRACKS TO QUEUE · "+shown.size()+" ITEMS");}
    }
    void backBrowse(){
        if(!browseHistory.isEmpty())restorePage(browseHistory.removeLast());
        else if(parent!=null)restorePage(new BrowsePage(new BrowseState(section,query,null,"","",0,0),null,false));
    }
    void invalidateBrowseHistory(){
        List<BrowsePage> pages=new ArrayList<>(browseHistory);browseHistory.clear();for(BrowsePage page:pages)browseHistory.addLast(new BrowsePage(page.state(),null,false));previousBrowseRows=null;
    }
    void navigate(String next){browseHistory.clear();previousBrowseRows=null;if(searchScreen){searchScreen=false;previousBrowse=null;hideKeyboard();mainScreen();}restoreScroll=0;restoreTop=0;section=next;parent=null;query="";selected.clear();selecting=false;search.setText("");load(false);}
    void searchNow(){handler.removeCallbacks(searchDebounce);if(!searchScreen){openSearch("");return;}query=search.getText().toString().trim();selected.clear();selecting=false;parent=null;restoreScroll=0;restoreTop=0;load(false);}
    void openSearch(String initial){
        if(!searchScreen){BrowsePage page=capturePage();previousBrowse=page.state();previousBrowseRows=page.rows();previousBrowseMore=page.more();}
        restoreScroll=0;restoreTop=0;generation++;searchScreen=true;section="Tracks";query=initial;parent=null;selected.clear();selecting=false;searchLayout(true);load(false);
    }
    void closeSearch(){
        handler.removeCallbacks(searchDebounce);hideKeyboard();generation++;searchScreen=false;
        BrowseState previous=previousBrowse;previousBrowse=null;List<Track> rows=previousBrowseRows;previousBrowseRows=null;
        mainScreen();if(previous!=null)restorePage(new BrowsePage(previous,rows,previousBrowseMore));else load(false);
    }
    void searchLayout(boolean keyboard){
        base();rail(root,"REWINDAMP SEARCH");LinearLayout navigation=row();weighted(navigation,button("‹ BACK",this::closeSearch),44);queueButton=button("QUEUE",()->navigate("Queue"));weighted(navigation,queueButton,44);root.addView(navigation);
        LinearLayout find=row();search=field("Track title or artist",false);search.setText(query);find.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));find.addView(button("DONE",this::hideKeyboard),new LinearLayout.LayoutParams(dp(54),dp(48)));root.addView(find);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.setOnEditorActionListener((v,a,event)->{searchNow();hideKeyboard();return true;});
        count=text("TYPE TO SEARCH · TAP RESULTS TO QUEUE",11,Retro.MUTED);root.addView(count,new LinearLayout.LayoutParams(-1,dp(32)));tools=row();root.addView(tools,new LinearLayout.LayoutParams(-1,dp(40)));sectionButton=null;
        buildResults();root.addView(resultScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row();status=text("Tap a result to add it without leaving search",10,Retro.MUTED);footer.addView(status,new LinearLayout.LayoutParams(0,dp(44),1));undoButton=button("UNDO",this::doUndo);footer.addView(undoButton,new LinearLayout.LayoutParams(dp(54),dp(44)));root.addView(footer);root.addView(Retro.navigationBar(this,false));
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){generation++;if(browseTask!=null)browseTask.cancel(true);morePages=false;loadingPage=true;handler.removeCallbacks(searchDebounce);handler.postDelayed(searchDebounce,300);}public void afterTextChanged(android.text.Editable value){}});
        updatePlayer();if(keyboard){EditText input=search;input.requestFocus();input.postDelayed(()->{if(searchScreen&&search==input&&!destroyed)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);},120);}
    }
    void hideKeyboard(){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
    void connectPlayer(){
        if(controllerFuture!=null)return;controllerFuture=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).buildAsync();
        controllerFuture.addListener(()->{try{if(destroyed)return;player=controllerFuture.get();player.addListener(new Player.Listener(){
            @Override public void onEvents(Player p,Player.Events events){updatePlayer();if(section.equals("Queue")&&(events.contains(Player.EVENT_TIMELINE_CHANGED)||events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)))load(false);}
            @Override public void onPlayerError(PlaybackException error){
                MediaItem current=player.getCurrentMediaItem();if(current!=null&&offline!=null&&offline.complete(current.mediaId)&&current.mediaMetadata.extras!=null&&!current.mediaMetadata.extras.getBoolean("offline"))say("Switching to downloaded copy…");else say("Playback failed. Try a downloaded track or check the connection.");
            }
        });if(!pendingQueue.isEmpty()){List<Track> pending=new ArrayList<>(pendingQueue);pendingQueue.clear();enqueue(pending);}updatePlayer();if(section.equals("Queue"))load(false);
        }catch(Exception ignored){say("Could not connect to the audio service. Reopen Rewindamp.");}},command->handler.post(command));
    }
    void resume(){if(demo){say("Demo preview — connect to play your music");return;}if(player==null)return;if(player.getMediaItemCount()==0){say("Tap a track to start building your queue");return;}if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);if(player.getPlaybackState()==Player.STATE_IDLE)player.prepare();player.play();}
    void updateClock(){
        if(clock==null||searchScreen)return;
        boolean hasTrack=!demo&&player!=null&&player.getCurrentMediaItem()!=null;
        long position=hasTrack?Math.max(0,player.getCurrentPosition()):0,duration=hasTrack?player.getDuration():0;
        String value=remainingTime?(hasTrack&&duration<0?"--:--":"-"+Track.time(Math.max(0,duration-position))):Track.time(position);
        clock.setTime(value);clock.setContentDescription((remainingTime?"Time remaining ":"Time elapsed ")+value+". Tap to show "+(remainingTime?"elapsed":"remaining")+" time.");
    }
    void updatePlayer(){
        updateClock();
        if(spectrum!=null)spectrum.setPlaybackActive(!demo&&player!=null&&player.isPlaying());
        if(queueButton==null)return;int size=player==null?pendingQueue.size():player.getMediaItemCount();queueButton.setText("QUEUE "+size);if(undoButton!=null){undoButton.setEnabled(undo!=null);undoButton.setAlpha(undo!=null?1f:.35f);}
        if(previous!=null){boolean enabled=!demo&&player!=null&&player.hasPreviousMediaItem();previous.setEnabled(enabled);previous.setAlpha(enabled?1f:.35f);}
        if(next!=null){boolean enabled=!demo&&player!=null&&player.hasNextMediaItem();next.setEnabled(enabled);next.setAlpha(enabled?1f:.35f);}
        if(searchScreen||demo||player==null)return;MediaItem current=player.getCurrentMediaItem();
        if(current!=null){Track t=Track.fromMedia(current);setLabel(title,t.name);setLabel(artist,t.artist.isBlank()?t.album:t.artist);
            if(!artId.equals(t.imageId+":"+t.imageTag)){artId=t.imageId+":"+t.imageTag;artwork.show(cover,t,api,store);}
        }else{setLabel(title,"READY WHEN YOU ARE");setLabel(artist,"TAP TRACKS TO QUEUE");}
        setLabel(audioInfo,player.getSessionExtras().getBoolean("crossfading")?"CROSSFADE":AudioDsp.sampleRate>0?String.format(Locale.US,"%d kHz · %s",AudioDsp.sampleRate/1000,AudioDsp.channels==1?"MONO":"STEREO"):"JELLYFIN AUDIO");
        long duration=player.getDuration();if(!seeking)seek.setProgress(duration>0?(int)(1000*player.getCurrentPosition()/duration):0);volume.setProgress(Math.round(player.getSessionExtras().getFloat("logical_volume",player.getVolume())*100));
        shuffle.setText(player.getShuffleModeEnabled()?"● SHUF":"SHUF");repeat.setText(switch(player.getRepeatMode()){case Player.REPEAT_MODE_ONE->"R·1";case Player.REPEAT_MODE_ALL->"R·ALL";default->"REP";});
    }
    void updateDownloads(){if(offline==null)return;downloadLabels.entrySet().removeIf(entry->!entry.getValue().isAttachedToWindow());for(var entry:downloadLabels.entrySet())entry.getValue().setText(offline.label(entry.getKey()));}
    void enqueue(List<Track> tracks){
        if(tracks.isEmpty())return;if(demo){title.setText(tracks.get(0).name);artist.setText("DEMO · CONNECT TO LISTEN");say("Demo preview — connect to build a queue");return;}
        if(player==null){pendingQueue.addAll(tracks);say("Queued "+tracks.size()+" · audio service connecting");return;}
        List<MediaItem> media=new ArrayList<>();for(Track track:tracks)if(track.type.equals("Audio"))media.add(offline.media(track));QueueStore.append(player,media);
        say((media.size()==1?"Added “"+tracks.get(0).name+"”":media.size()+" tracks added")+" · "+player.getMediaItemCount()+" queued");updatePlayer();
    }
    void load(boolean more){
        if(more&&(loadingPage||!morePages||pageFailed))return;
        if(!more&&browseTask!=null)browseTask.cancel(true);
        loadingPage=false;pageFailed=false;
        int request=++generation;String requestedSection=section,requestedParent=parent,requestedType=parentType,requestedQuery=query;int offset=more?shown.size():0;
        if(searchScreen&&requestedQuery.isBlank()){shown.clear();render(false);return;}
        if(section.equals("Queue")){shown.clear();if(player!=null)for(int i=0;i<player.getMediaItemCount();i++)shown.add(Track.fromMedia(player.getMediaItemAt(i)));else shown.addAll(pendingQueue);render(false);return;}
        if(demo){shown.clear();shown.addAll(demoItems());render(false);return;}
        if(section.equals("Offline")){shown.clear();for(Track t:offline.tracks(false))if(matches(t,query))shown.add(t);render(false);return;}
        loadingPage=true;if(!more){shown.clear();render(false);}else resultAdapter.notifyDataSetChanged();
        String cacheKey="browse:"+requestedSection+":"+requestedParent+":"+requestedQuery+":"+offset+(requestedParent==null&&(requestedSection.equals("Artists")||requestedSection.equals("Albums"))?":artist-order-v1":"");say("Loading…");JellyfinApi requestedApi=api;LibraryStore requestedStore=store;
        browseTask=(searchScreen?searchIo:io).submit(()->{try{
            List<Track> tracks=requestedApi.browse(requestedSection,requestedQuery,requestedParent,requestedType,offset);requestedStore.tracks(cacheKey,tracks);if(requestedSection.equals("Favourites")&&requestedParent==null&&offset==0&&requestedQuery.isBlank())requestedStore.tracks("favourites-local",tracks);
            handler.post(()->{if(destroyed||request!=generation)return;loadingPage=false;if(!more)shown.clear();shown.addAll(tracks);render(tracks.size()==100&&!(requestedParent!=null&&requestedType.equals("Playlist")));say("TAP TRACKS TO QUEUE · "+shown.size()+" ITEMS");});
        }catch(Exception ex){List<Track> cached=requestedStore.tracks(cacheKey);if(requestedSection.equals("Favourites")&&offset==0&&requestedQuery.isBlank())cached=requestedStore.tracks("favourites-local");List<Track> fallback=cached;
            handler.post(()->{if(destroyed||request!=generation)return;loadingPage=false;if(!more)shown.clear();shown.addAll(fallback);pageFailed=true;render(true);say(fallback.isEmpty()?message(ex):"OFFLINE · CACHED LIBRARY / DOWNLOADED TRACKS PLAY");});
        }});
    }
    boolean matches(Track track,String term){return (track.name+" "+track.artist+" "+track.album).toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));}
    boolean playlist(){return parent!=null&&parentType.equals("Playlist");}
    boolean queue(){return section.equals("Queue");}
    List<Track> selectedTracks(){List<Track> tracks=new ArrayList<>();for(int index:selected)if(index<shown.size())tracks.add(shown.get(index));return tracks;}
    void render(boolean more){
        if(resultAdapter==null)return;morePages=more;if(sectionButton!=null)sectionButton.setText((queue()?"LIBRARY":section).toUpperCase(Locale.ROOT)+" ▾");
        count.setText((parent!=null?parentName:section).toUpperCase(Locale.ROOT)+" · "+shown.size()+" ITEMS"+(selecting?" · "+selected.size()+" SELECTED":""));search.setHint(searchScreen?"Track title or artist":"Search "+section.toLowerCase(Locale.ROOT));tools.removeAllViews();
        if(selecting){
            weighted(tools,button("+ QUEUE",()->{enqueue(selectedTracks());selecting=false;selected.clear();render(morePages);}),40);
            weighted(tools,button("+ PL",()->choosePlaylist(selectedTracks())),40);weighted(tools,button("SAVE ↓",()->download(selectedTracks())),40);
            if(queue()||playlist())weighted(tools,button("REMOVE",this::removeSelected),40);
            weighted(tools,button("ALL",()->{for(int i=0;i<shown.size();i++)if(shown.get(i).type.equals("Audio"))selected.add(i);render(morePages);}),40);
        }else{
            if(!searchScreen&&(parent!=null||!browseHistory.isEmpty()))weighted(tools,button("‹ BACK",this::backBrowse),40);
            if(section.equals("Playlists")&&parent==null)weighted(tools,button("NEW",()->nameDialog("New private playlist","",name->mutate(()->{api.create(name,List.of());return null;},()->load(false)))),40);
            if(playlist()){
                weighted(tools,button("RENAME",()->{String id=parent;nameDialog("Rename playlist",parentName,name->mutate(()->{api.rename(id,name);return null;},()->{parentName=name;load(false);}));}),40);
                weighted(tools,button("COPY",()->{List<Track> snapshot=new ArrayList<>(shown);nameDialog("Duplicate playlist",parentName+" copy",name->mutate(()->{api.create(name,snapshot);return null;},()->say("Private playlist copy created")));}),40);
            }
            if(shown.stream().anyMatch(t->t.type.equals("Audio"))){
                if(!queue())weighted(tools,button("ADD ALL",()->enqueue(new ArrayList<>(shown))),40);
                if(!queue())weighted(tools,button("SAVE ↓",()->download(new ArrayList<>(shown))),40);
            }
            if(queue()){
                weighted(tools,button("SAVE AS",()->{List<Track> snapshot=new ArrayList<>(shown);nameDialog("Save queue as playlist","",name->mutate(()->{api.create(name,snapshot);return null;},()->say("Playlist saved to Jellyfin")));}),40);
                weighted(tools,button("CLEAR QUEUE",this::clearQueue),40);
            }
            weighted(tools,button("↻",()->load(false)),40);
        }
        visibleRows.clear();for(int i=0;i<shown.size();i++)if(!queue()||matches(shown.get(i),query))visibleRows.add(i);
        resultAdapter.notifyDataSetChanged();
        if(restoreScroll>=0&&(restoreScroll<visibleRows.size()||(!morePages&&!loadingPage)||pageFailed)){
            int position=Math.min(restoreScroll,Math.max(0,visibleRows.size()-1)),top=restoreTop;restoreScroll=-1;
            ListView list=resultScroll;int request=generation;list.post(()->{if(!destroyed&&list==resultScroll&&request==generation)list.setSelectionFromTop(position,top);});
        }
        resultScroll.post(this::loadNearEnd);updatePlayer();
    }
    void buildResults(){
        resultScroll=new ListView(this);resultScroll.setBackground(Retro.bevel(Retro.LCD,true));resultScroll.setDivider(null);resultScroll.setPadding(dp(3),dp(3),dp(3),dp(3));resultScroll.setClipToPadding(true);
        resultAdapter=new BaseAdapter(){
            @Override public int getCount(){return Math.max(1,visibleRows.size())+(!visibleRows.isEmpty()&&(morePages||loadingPage)?1:0);}
            @Override public Object getItem(int position){return position<visibleRows.size()?shown.get(visibleRows.get(position)):null;}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View convertView,ViewGroup parentView){
                if(position<visibleRows.size()){int index=visibleRows.get(position);return addRow(shown.get(index),index);}
                if(pageFailed)return button("RETRY CONNECTION",()->{pageFailed=false;load(!shown.isEmpty());});
                String message=loadingPage||morePages?"Loading…":searchScreen&&query.isBlank()?"FIND YOUR NEXT TRACK\n\nType above. Tap results to add them.\nYour current queue keeps playing.":queue()?"YOUR QUEUE IS QUIET\n\nTap tracks in the library to add them.\nYour queue stays until you clear it here.":section.equals("Offline")?"NO DOWNLOADS YET\n\nOpen a playlist or album and tap SAVE ↓.\nDownloads stay on this phone.":"NO MUSIC FOUND\n\nTry another search or refresh.\nDownloaded music is in Library → Offline.";
                TextView hint=text(message,12,Retro.MUTED);hint.setPadding(dp(12),dp(18),dp(12),dp(18));return hint;
            }
        };
        resultScroll.setAdapter(resultAdapter);
        resultScroll.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int state){}public void onScroll(AbsListView view,int first,int visible,int total){loadNearEnd();}});
    }
    void loadNearEnd(){
        if(destroyed||loadingPage||pageFailed||!morePages||resultScroll.getHeight()==0)return;
        if(restoreScroll>=shown.size()||resultScroll.getLastVisiblePosition()+20>=visibleRows.size())load(true);
    }
    View addRow(Track track,int index){
        LinearLayout item=row();item.setPadding(dp(4),dp(2),dp(2),dp(2));boolean current=queue()&&player!=null&&player.getCurrentMediaItemIndex()==index;
        item.setBackgroundColor(selected.contains(index)?0xFF24402C:current?0xFF162319:Retro.LCD);
        if(selecting){CheckBox check=new CheckBox(this);check.setButtonTintList(ColorStateList.valueOf(Retro.GREEN));check.setChecked(selected.contains(index));check.setContentDescription("Select "+track.name);item.addView(check,new LinearLayout.LayoutParams(dp(36),dp(48)));check.setOnCheckedChangeListener((b,value)->{if(value)selected.add(index);else selected.remove(index);render(morePages);});}
        else if(!track.type.equals("Audio")||(!queue()&&!playlist())){
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);item.addView(image,new LinearLayout.LayoutParams(dp(36),dp(36)));artwork.show(image,track,api,store);
        }else{TextView number=text(String.format(Locale.US,"%d.",index+1),12,Retro.GREEN);item.addView(number,new LinearLayout.LayoutParams(dp(28),dp(48)));}
        LinearLayout detail=column();detail.setPadding(dp(6),dp(4),dp(4),dp(4));TextView name=text(track.name,13,current?Retro.WHITE:Retro.GREEN);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);detail.addView(name);
        TextView subtitle=text(track.subtitle(),9,Retro.MUTED);subtitle.setSingleLine(true);subtitle.setEllipsize(TextUtils.TruncateAt.END);detail.addView(subtitle);detail.setMinimumHeight(dp(48));item.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
        if(section.equals("Offline")){TextView progress=text(offline==null?"":offline.label(track.id),8,Retro.GOLD);detail.addView(progress);downloadLabels.put(track.id,progress);}
        Boolean override=store==null?null:store.favoriteOverride(track.id);boolean favorite=override==null?track.favorite:override;
        TextView duration=text((favorite?"★ ":"")+(track.type.equals("Audio")?Track.time(track.duration):""),10,Retro.GREEN);item.addView(duration);
        detail.setOnClickListener(v->{if(selecting){if(!selected.remove(index))selected.add(index);render(morePages);return;}if(track.type.equals("Audio")){if(queue()&&player!=null){player.seekTo(index,0);player.prepare();player.play();}else enqueue(List.of(track));}else open(track);});
        detail.setOnLongClickListener(v->{selecting=true;selected.add(index);render(morePages);return true;});
        if(track.type.equals("Audio")){
            Button action=button("+",()->trackMenu(track,index));item.addView(action,new LinearLayout.LayoutParams(dp(42),dp(48)));
            if(queue()||playlist()){
                TextView drag=text("≡",18,Retro.MUTED);drag.setGravity(Gravity.CENTER);drag.setContentDescription("Drag to reorder "+track.name);item.addView(drag,new LinearLayout.LayoutParams(dp(30),dp(48)));
                drag.setOnLongClickListener(v->{v.startDragAndDrop(ClipData.newPlainText("rewindamp-row",String.valueOf(index)),new View.DragShadowBuilder(item),new DragToken(generation,index),0);return true;});
                item.setOnDragListener((v,event)->{if(!(event.getLocalState() instanceof DragToken token)||token.generation!=generation)return false;if(event.getAction()==DragEvent.ACTION_DROP){move(token.index,index);return true;}return true;});
            }
        }else item.addView(button("›",()->open(track)),new LinearLayout.LayoutParams(dp(42),dp(48)));
        item.setLayoutParams(new AbsListView.LayoutParams(-1,-2));return item;
    }
    record DragToken(int generation,int index){}
    void open(Track track){browseHistory.addLast(capturePage());restoreScroll=0;restoreTop=0;parent=track.id;parentName=track.name;parentType=track.type;selected.clear();selecting=false;load(false);}
    void move(int from,int to){if(from==to||from<0||to<0||from>=shown.size()||to>=shown.size())return;if(demo){say("Connect to edit playlists");return;}
        if(queue()){if(player!=null){player.moveMediaItem(from,to);load(false);}return;}
        String id=parent;Track track=shown.get(from);mutate(()->{api.move(id,track,to);return null;},()->{setUndo(()->mutate(()->{api.move(id,track,from);return null;},()->load(false)));load(false);say("Playlist reordered");});
    }
    void trackMenu(Track track,int index){
        Boolean override=store==null?null:store.favoriteOverride(track.id);boolean favorite=override==null?track.favorite:override;
        List<String> labels=new ArrayList<>(List.of("Add to queue","Play next","Play now (keep queue)","Add to playlist…",favorite?"Remove favourite":"Add favourite","Download for offline"));
        if(queue()||playlist())labels.addAll(List.of("Move up","Move down",queue()?"Remove from queue":"Remove from playlist"));
        if(section.equals("Offline"))labels.addAll(List.of("Retry download","Remove download from phone"));
        new AlertDialog.Builder(this).setTitle(track.name).setItems(labels.toArray(new String[0]),(d,which)->{
            if(demo){say("Connect to Jellyfin to manage your music");return;}
            switch(which){
                case 0->enqueue(List.of(track));
                case 1->{if(player!=null){player.addMediaItem(Math.max(0,player.getCurrentMediaItemIndex()+1),offline.media(track));if(player.getMediaItemCount()==1)resume();say("Added next");}}
                case 2->{if(player!=null){int at=player.getMediaItemCount();player.addMediaItem(offline.media(track));player.seekTo(at,0);player.prepare();player.play();}}
                case 3->choosePlaylist(List.of(track));
                case 4->toggleFavorite(track,!favorite);
                case 5->download(List.of(track));
                default->{if(queue()||playlist()){if(which==6)move(index,index-1);else if(which==7)move(index,index+1);else removeIndices(List.of(index));}
                    else if(section.equals("Offline")){if(which==6)download(List.of(track));else confirmRemoveDownload(track);}}
            }
        }).show();
    }
    void clearQueue(){if(player==null||player.getMediaItemCount()==0)return;List<MediaItem> removed=new ArrayList<>();for(int i=0;i<player.getMediaItemCount();i++)removed.add(player.getMediaItemAt(i));player.pause();player.clearMediaItems();setUndo(()->{player.addMediaItems(0,removed);load(false);say("Queue restored");});load(false);say("Queue cleared · Undo available");}
    void removeSelected(){removeIndices(new ArrayList<>(selected));}
    void removeIndices(List<Integer> indices){
        if(indices.isEmpty()||demo)return;List<Integer> ordered=new ArrayList<>(indices);Collections.sort(ordered);List<Track> removed=new ArrayList<>();for(int index:ordered)if(index<shown.size())removed.add(shown.get(index));
        if(queue()&&player!=null){List<MediaItem> media=new ArrayList<>();for(int index:ordered)media.add(player.getMediaItemAt(index));for(int i=ordered.size()-1;i>=0;i--)player.removeMediaItem(ordered.get(i));setUndo(()->{for(int i=0;i<media.size();i++)player.addMediaItem(Math.min(ordered.get(i),player.getMediaItemCount()),media.get(i));load(false);});selected.clear();selecting=false;load(false);say("Removed from queue · Undo available");}
        else if(playlist()){String id=parent;mutate(()->{api.removeMany(id,removed);return null;},()->{setUndo(()->mutate(()->{for(int i=0;i<removed.size();i++)api.restoreEntry(id,removed.get(i),ordered.get(i));return null;},()->load(false)));selected.clear();selecting=false;load(false);say("Removed playlist entries · Undo available");});}
    }
    void setUndo(Runnable action){undo=action;undoTime=System.currentTimeMillis();if(undoButton!=null)undoButton.setEnabled(true);}
    void doUndo(){Runnable action=undo;undo=null;if(action!=null)action.run();updatePlayer();}
    void toggleFavorite(Track track,boolean value){
        store.editFavorite(track,value);invalidateBrowseHistory();for(int i=0;i<shown.size();i++)if(shown.get(i).id.equals(track.id))shown.set(i,shown.get(i).withFavorite(value));render(morePages);say(value?"Favourite saved · syncs with Jellyfin":"Favourite removed · syncs with Jellyfin");io.execute(()->new PlaybackReporter(api,store).sync());
    }
    void choosePlaylist(List<Track> tracks){if(tracks.isEmpty())return;if(demo){say("Connect to manage playlists");return;}
        work(()->{List<Track> all=new ArrayList<>();for(int start=0;;start+=100){List<Track> page=api.items("Playlist","",null,start);all.addAll(page);if(page.size()<100)return all;}},lists->{
            List<String> names=new ArrayList<>();names.add("+ New playlist");for(Track list:lists)names.add(list.name);
            new AlertDialog.Builder(this).setTitle("Add "+tracks.size()+" tracks to playlist").setItems(names.toArray(new String[0]),(d,i)->{
                if(i==0)nameDialog("New private playlist","",name->mutate(()->{api.create(name,tracks);return null;},()->say("Playlist created")));
                else mutate(()->{api.addMany(lists.get(i-1).id,tracks);return null;},()->say("Added to "+lists.get(i-1).name));
            }).setNegativeButton("Cancel",null).show();
        });
    }
    void download(List<Track> tracks){
        if(demo){say("Connect to download music");return;}List<Track> audio=new ArrayList<>();for(Track track:tracks)if(track.type.equals("Audio"))audio.add(track);if(audio.isEmpty())return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},9);
        try{offline.enqueue(audio);say("Downloading "+audio.size()+" tracks · view Library → Offline");io.execute(()->{for(Track track:audio)try{if(store.artwork(track).length==0)store.artwork(track,api.image(track));}catch(Exception ignored){}});}catch(Exception ex){say("Could not start downloads. Reconnect and try again.");}
    }
    void confirmRemoveDownload(Track track){new AlertDialog.Builder(this).setTitle("Remove downloaded copy?").setMessage("Only the copy on this phone is removed. The Jellyfin library is unchanged.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{offline.remove(track.id);handler.postDelayed(()->load(false),500);}).show();}
    <T> void work(Callable<T> task,Consumer<T> done){
        if(busy){say("A playlist request is already running. Queueing still works.");return;}busy=true;JellyfinApi expected=api;say("Working…");
        io.execute(()->{try{T value=task.call();handler.post(()->{if(destroyed||expected!=api)return;busy=false;done.accept(value);});}catch(Exception ex){handler.post(()->{if(destroyed||expected!=api)return;busy=false;say(message(ex));});}});
    }
    void mutate(Callable<Void> task,Runnable done){if(demo){say("Connect to edit playlists");return;}work(task,x->{invalidateBrowseHistory();done.run();});}
    void nameDialog(String title,String initial,Consumer<String> action){
        EditText input=field("Playlist name",false);input.setText(initial);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isBlank()){input.setError("Enter a name");return;}dialog.dismiss();action.accept(name);}));dialog.show();
    }
    void settings(){
        String[] choices=demo?new String[]{"Connect to Jellyfin","About Rewindamp"}:new String[]{"Playback transitions","Downloads","Pause downloads","Resume downloads","Sync favourites and play history","Sign out","About Rewindamp"};
        new AlertDialog.Builder(this).setTitle("Rewindamp 0.4.5").setItems(choices,(d,i)->{
            if(demo){if(i==0){demo=false;loginScreen();}else about();return;}
            switch(i){case 0->TransitionSettings.show(this);case 1->navigate("Offline");case 2->{offline.paused(true);say("Downloads paused");}case 3->{offline.paused(false);say("Downloads resumed");}
                case 4->{say("Syncing…");io.execute(()->{new PlaybackReporter(api,store).sync();handler.post(()->say("Sync attempted · pending changes retry automatically"));});}
                case 5->new AlertDialog.Builder(this).setTitle("Sign out?").setMessage("Playback and downloads stop. Saved queues and downloads stay private to this account on the phone.").setNegativeButton("Cancel",null).setPositiveButton("Sign out",(dd,w)->signOut()).show();default->about();}
        }).setNegativeButton("Close",null).show();
    }
    void about(){new AlertDialog.Builder(this).setTitle("Rewindamp 0.4.5").setMessage("Classic controls. Your Jellyfin music.\n\nTap tracks to append. Clear only in Queue.\nHold ≡ to drag playlist or queue entries. Long-press a track to select several.\n\n10-band EQ with automatic headroom. The spectrum follows decoded audio; no microphone permission.\n\nAndroid Auto requires sign-in on your phone. For a sideloaded build, enable unknown sources in Android Auto developer settings.\n\nIndependent app; not affiliated with Winamp or Jellyfin.").setPositiveButton("Close",null).show();}
    void signOut(){
        generation++;busy=false;JellyfinApi previous=api;if(player!=null)player.pause();if(controllerFuture!=null){MediaController.releaseFuture(controllerFuture);controllerFuture=null;}player=null;
        stopService(new Intent(this,PlaybackService.class));OfflineLibrary.pause(this);new androidx.media3.exoplayer.scheduler.PlatformScheduler(this,42).cancel();SessionStore.clear(this);browseHistory.clear();previousBrowseRows=null;previousBrowse=null;api=null;offline=null;store=null;undo=null;pendingQueue.clear();shown.clear();parent=null;section="Tracks";query="";loginScreen();
        io.execute(()->{try{previous.request("POST","/Sessions/Logout",null);}catch(Exception ignored){}});
    }
    void say(String message){if(status!=null)status.setText(message);}
    String message(Exception ex){String text=ex.getMessage();if(text!=null&&(ex instanceof IllegalArgumentException||text.startsWith("Jellyfin")||text.startsWith("Sign-in")||text.startsWith("Your Jellyfin")||text.startsWith("This server")))return text;return "Cannot reach Jellyfin · downloaded music is available in Offline";}
    List<Track> demoItems(){
        if(section.equals("Queue")||section.equals("Offline")||section.equals("History"))return List.of();
        if(section.equals("Playlists")&&parent==null)return List.of(new Track("list1","Late night transmission","","","Playlist","",0),new Track("list2","The good old days","","","Playlist","",0));
        if((section.equals("Albums")&&parent==null)||parentType.equals("MusicArtist")&&parent!=null)return List.of(new Track("album1","Midnight FM","Neon District","","MusicAlbum","",0),new Track("album3","1997","Pixel Coast","","MusicAlbum","",0),new Track("album5","Night Drive","Soft Signal","","MusicAlbum","",0),new Track("album2","Side A","The Cassette Club","","MusicAlbum","",0));
        List<Track> tracks=List.of(new Track("demo1","After the last train","Neon District","Midnight FM","Audio","one",243000),new Track("demo2","Magnetic memories","The Cassette Club","Side A","Audio","two",218000),new Track("demo3","Dial-up dreams","Pixel Coast","1997","Audio","three",196000),new Track("demo4","A little more static","Neon District","Midnight FM","Audio","four",271000),new Track("demo5","Somewhere on the highway","Soft Signal","Night Drive","Audio","five",232000));
        List<Track> filtered=new ArrayList<>();for(Track track:tracks)if(matches(track,query))filtered.add(track);
        java.util.Comparator<Track> byTitle=java.util.Comparator.comparing(t->t.name,String.CASE_INSENSITIVE_ORDER);
        filtered.sort(section.equals("Artists")&&parent==null?java.util.Comparator.comparing((Track t)->t.artist,String.CASE_INSENSITIVE_ORDER).thenComparing(byTitle):byTitle);return filtered;
    }
    @Override public void onBackPressed(){if(searchScreen){closeSearch();return;}if(selecting){selecting=false;selected.clear();render(morePages);}else if(parent!=null||!browseHistory.isEmpty()){backBrowse();}else super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;if(spectrum!=null)spectrum.setPlaybackActive(false);handler.removeCallbacksAndMessages(null);artwork.close();if(controllerFuture!=null)MediaController.releaseFuture(controllerFuture);io.shutdownNow();searchIo.shutdownNow();super.onDestroy();}
}
