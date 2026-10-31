package dev.rewindamp;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class LibraryUxIntegrationTest {
    final AdvancedIntegrationTest h=new AdvancedIntegrationTest();
    final class Browser implements AutoCloseable {
        final AdvancedIntegrationTest.PagedLibrary server=h.new PagedLibrary();
        final JellyfinApi api=server.api();
        final LibraryStore store=new LibraryStore(h.context,api);
        MainActivity activity;
        MediaController controller;
        Browser()throws Exception{h.context.getSharedPreferences("podcast-session",0).edit().clear().commit();SessionStore.save(h.context,api);}
        void start()throws Exception{
            activity=(MainActivity)h.instrumentation.startActivitySync(new Intent(h.context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            var future=h.read(()->new MediaController.Builder(h.context,new SessionToken(h.context,new ComponentName(h.context,PlaybackService.class))).buildAsync());controller=future.get(15,TimeUnit.SECONDS);
            h.main(()->{controller.pause();controller.clearMediaItems();});
        }
        View content(){return activity.findViewById(android.R.id.content);}
        ListView list(){return h.findList(content());}
        int requests(){synchronized(server.paths){return (int)server.paths.stream().filter(p->p.contains("IncludeItemTypes=Audio")&&!p.contains("/Artists?")).count();}}
        void ready(int rows)throws Exception{h.await(()->h.read(()->list().getCount()==rows),10);}
        void recreate()throws Exception{
            android.app.Instrumentation.ActivityMonitor monitor=new android.app.Instrumentation.ActivityMonitor(MainActivity.class.getName(),null,false);h.instrumentation.addMonitor(monitor);h.main(activity::recreate);
            Activity replacement=monitor.waitForActivityWithTimeout(10000);h.instrumentation.removeMonitor(monitor);assertNotNull(replacement);activity=(MainActivity)replacement;h.instrumentation.waitForIdleSync();
        }
        @Override public void close()throws Exception{h.main(()->{if(controller!=null){controller.pause();controller.clearMediaItems();controller.release();}activity.finish();h.context.stopService(new Intent(h.context,PlaybackService.class));OfflineLibrary.pause(h.context);});SessionStore.clear(h.context);server.close();}
    }
    <T> T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){T match=find(group.getChildAt(i),type);if(match!=null)return match;}return null;}
    void turn(Browser b,boolean forward){h.main(()->find(b.content(),DestinationWheel.class).performAccessibilityAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null));}
    void position(Browser b,int row,int top)throws Exception{h.main(()->b.list().setSelectionFromTop(row,top));h.await(()->h.read(()->b.list().getFirstVisiblePosition()==row&&b.list().getChildAt(0).getTop()==b.list().getPaddingTop()+top),5);}
    void pull(Browser b)throws Exception{
        h.main(()->{b.list().setSelectionFromTop(0,0);});h.instrumentation.waitForIdleSync();
        h.main(()->{View panel=find(b.content(),PullRefreshLayout.class);long now=SystemClock.uptimeMillis();float d=h.context.getResources().getDisplayMetrics().density;
            for(int i=0;i<4;i++){MotionEvent e=MotionEvent.obtain(now,now+i*100,i==0?MotionEvent.ACTION_DOWN:i==3?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,40*d,(10+i*35)*d,0);panel.dispatchTouchEvent(e);e.recycle();}
        });
    }
    void screenshot(String name)throws Exception{try(var descriptor=h.instrumentation.getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/rewindamp-"+name+".png");var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){input.readAllBytes();}}
    @Test public void unclutteredPanelHasFullHeightWheelAndEndOfListAddAll()throws Exception{
        try(Browser b=new Browser()){
            b.server.alphabetCatalog=true;b.start();b.ready(226);
            assertNull(h.read(()->h.lastText(b.content(),"SEL")));assertNull(h.read(()->h.lastText(b.content(),"↻")));assertNull(h.read(()->h.lastText(b.content(),"+")));assertNull(h.read(()->h.lastText(b.content(),"ADD ALL")));
            assertEquals(h.read(()->b.list().getHeight()),h.read(()->find(b.content(),LetterWheel.class).getHeight()));
            assertTrue(h.read(()->find(b.content(),DestinationWheel.class).getContentDescription().toString().contains("Music selected")));
            position(b,162,0);screenshot("library-049");
            h.main(()->b.list().setSelection(225));h.await(()->h.read(()->h.lastText(b.content(),"ADD ALL")!=null),5);
            assertNull(h.read(()->b.list().getAdapter().getItem(225)));position(b,0,0);
            h.main(()->b.list().getChildAt(0).performLongClick());h.instrumentation.waitForIdleSync();
            assertFalse(h.instrumentation.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Play next").isEmpty());h.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        }
    }
    @Test public void queueAndPodcastDestinationsPreserveBrowseAndSearchPositions()throws Exception{
        try(Browser b=new Browser()){
            b.start();b.ready(226);position(b,140,-9);int top=h.read(()->b.list().getChildAt(0).getTop());
            h.main(()->{b.list().getChildAt(0).performClick();b.controller.pause();});h.await(()->h.read(()->b.controller.getMediaItemCount()==1),5);
            turn(b,false);h.await(()->h.read(()->b.activity.queue()),5);b.ready(1);screenshot("queue-049");turn(b,true);b.ready(226);
            h.await(()->h.read(()->b.list().getFirstVisiblePosition()==140),5);assertEquals(top,(int)h.read(()->b.list().getChildAt(0).getTop()));
            h.main(()->b.activity.openSearch("Paged"));b.ready(226);h.main(b.activity::hideKeyboard);h.instrumentation.waitForIdleSync();position(b,90,-7);int searchTop=h.read(()->b.list().getChildAt(0).getTop());
            turn(b,false);b.ready(1);
            android.os.Bundle beforeRecreate=new android.os.Bundle();h.main(()->b.activity.onSaveInstanceState(beforeRecreate));assertEquals(90,beforeRecreate.getBundle("musicPage").getInt("scroll"));
            b.recreate();android.os.Bundle afterRecreate=new android.os.Bundle();h.main(()->b.activity.onSaveInstanceState(afterRecreate));assertEquals(90,afterRecreate.getBundle("musicPage").getInt("scroll"));h.await(()->h.read(()->b.activity.queue()),5);turn(b,true);b.ready(226);
            try{h.await(()->h.read(()->b.list().getFirstVisiblePosition()==90),5);}catch(AssertionError e){throw new AssertionError(h.read(()->"Search restored to "+b.activity.captureBrowse()+", rows="+b.list().getCount()),e);}assertEquals(searchTop,(int)h.read(()->b.list().getChildAt(0).getTop()));assertEquals("Paged",h.read(()->h.findInput(b.content()).getText().toString()));
            h.main(b.activity::closeSearch);b.ready(226);h.await(()->h.read(()->b.list().getFirstVisiblePosition()==140),5);
            android.app.Instrumentation.ActivityMonitor monitor=new android.app.Instrumentation.ActivityMonitor(PodcastActivity.class.getName(),null,false);h.instrumentation.addMonitor(monitor);turn(b,true);Activity podcasts=monitor.waitForActivityWithTimeout(10000);h.instrumentation.removeMonitor(monitor);assertNotNull(podcasts);
            h.main(()->find(podcasts.findViewById(android.R.id.content),DestinationWheel.class).performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));h.await(()->h.read(()->b.activity.queue()),5);turn(b,true);b.ready(226);h.await(()->h.read(()->b.list().getFirstVisiblePosition()==140),5);
        }
    }
    @Test public void cachedLaunchAndSilentRefreshKeepTheVisibleTrackAndStatus()throws Exception{
        try(Browser b=new Browser()){
            List<Track> saved=new ArrayList<>();for(int i=0;i<225;i++)saved.add(new Track("paged-"+i,String.format(Locale.US,"Paged %03d",i),"Fixture Artist","","Audio","",1000));b.store.tracks("music-catalog-v1",saved);
            b.server.delayMillis=1500;b.start();b.ready(226);assertNull(h.read(()->h.lastText(b.content(),"Loading…")));position(b,100,-7);int top=h.read(()->b.list().getChildAt(0).getTop());
            b.server.extraFirst=true;h.main(()->b.activity.say("Added track to queue"));b.ready(227);h.await(()->h.read(()->b.list().getFirstVisiblePosition()==101),5);
            assertEquals(top,(int)h.read(()->b.list().getChildAt(0).getTop()));assertEquals("paged-100",h.read(()->((Track)b.list().getAdapter().getItem(101)).id));assertNotNull(h.read(()->h.lastText(b.content(),"Added track to queue")));
            b.server.delayMillis=0;b.server.failNext=true;final int failedBefore=b.requests();pull(b);h.await(()->b.requests()==failedBefore+1,5);h.await(()->h.read(()->h.lastText(b.content(),"Refresh failed · saved library retained")!=null),5);assertEquals(227,(int)h.read(()->b.list().getCount()));assertNull(h.read(()->h.lastText(b.content(),"RETRY CONNECTION")));
            final int before=b.requests();pull(b);h.await(()->b.requests()==before+1,5);h.await(()->h.read(()->h.lastText(b.content(),"TRACKS · 226 ITEMS · refreshed")!=null),5);
            b.server.delayMillis=1500;h.main(()->b.activity.navigate("Artists"));assertEquals(227,(int)h.read(()->b.list().getCount()));assertNull(h.read(()->h.lastText(b.content(),"Loading…")));
            b.recreate();b.ready(227);assertNull(h.read(()->h.lastText(b.content(),"Loading…")));
            LibraryStore otherUser=new LibraryStore(h.context,new JellyfinApi(b.api.server,"other-test-token","different-user","test-device"));assertNull(otherUser.cachedTracks("music-catalog-v1"));
        }
    }
    @Test public void cachedEmptyCatalogueDoesNotShowLoadingOnRelaunch()throws Exception{
        try(Browser b=new Browser()){
            b.store.tracks("music-catalog-v1",List.of());b.server.delayMillis=1800;b.start();
            assertNull(h.read(()->h.lastText(b.content(),"Loading…")));assertNotNull(h.read(()->h.lastText(b.content(),"TRACKS · 0 ITEMS")));b.ready(226);
        }
    }
}
