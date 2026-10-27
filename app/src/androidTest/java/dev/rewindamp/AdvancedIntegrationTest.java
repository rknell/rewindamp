package dev.rewindamp;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class AdvancedIntegrationTest {
    final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    final Context context=instrumentation.getTargetContext();
    void main(Runnable action){instrumentation.runOnMainSync(action);}
    <T> T read(java.util.concurrent.Callable<T> action){AtomicReference<T> result=new AtomicReference<>();main(()->{try{result.set(action.call());}catch(Exception e){throw new RuntimeException(e);}});return result.get();}
    void await(BooleanSupplier condition,int seconds)throws Exception{long end=System.currentTimeMillis()+seconds*1000L;while(System.currentTimeMillis()<end){if(condition.getAsBoolean())return;Thread.sleep(100);}assertTrue("Timed out waiting for the expected state",condition.getAsBoolean());}
    JellyfinApi login()throws Exception{return JellyfinApi.login("http://10.0.2.2:18096","rewindamp-test","test-only-local-password");}
    final class Fixture implements AutoCloseable {
        final JellyfinApi api;
        Activity activity;
        MediaController controller;
        OfflineLibrary offline;
        Fixture()throws Exception{api=login();SessionStore.save(context,api);start();main(()->{controller.pause();controller.clearMediaItems();});}
        void start()throws Exception{
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
            var future=read(()->new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PlaybackService.class))).buildAsync());controller=future.get(15,TimeUnit.SECONDS);offline=read(()->OfflineLibrary.get(context,api));
        }
        void stop()throws Exception{main(()->{controller.pause();controller.release();activity.finish();context.stopService(new Intent(context,PlaybackService.class));});Thread.sleep(500);}
        void play(Track track)throws Exception{main(()->{controller.setMediaItem(offline.media(track));controller.prepare();controller.play();});await(()->read(()->controller.isPlaying()&&controller.getCurrentPosition()>200),15);}
        @Override public void close()throws Exception{
            main(()->{controller.pause();controller.clearMediaItems();});Thread.sleep(300);stop();SessionStore.clear(context);main(()->OfflineLibrary.pause(context));
            // Allow queued stop reports to finish before revoking this disposable session.
            new PlaybackReporter(api,offline.store).sync();api.request("POST","/Sessions/Logout",null);
        }
    }
    android.view.View lastText(android.view.View view,String text){
        android.view.View found=null;if(view instanceof android.widget.TextView tv&&text.contentEquals(tv.getText()))found=view;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.view.View match=lastText(group.getChildAt(i),text);if(match!=null)found=match;}
        return found;
    }
    @Test public void libraryTapsAppendAndQueueClearHasUndo()throws Exception{
        try(Fixture f=new Fixture()){
            List<Track> tracks=f.api.items("Audio","",null,0);android.view.View content=f.activity.findViewById(android.R.id.content);
            await(()->read(()->lastText(content,tracks.get(1).name)!=null),10);
            main(()->((android.view.View)lastText(content,tracks.get(0).name).getParent()).performClick());
            main(()->((android.view.View)lastText(content,tracks.get(1).name).getParent()).performClick());
            await(()->read(()->f.controller.getMediaItemCount()==2),10);
            assertEquals(tracks.get(0).id,read(()->f.controller.getMediaItemAt(0).mediaId));
            main(()->((android.view.View)lastText(content,tracks.get(0).name).getParent()).performClick());
            await(()->read(()->f.controller.getMediaItemCount()==3),10);
            assertEquals(0,(int)read(()->f.controller.getCurrentMediaItemIndex()));
            main(()->lastText(content,"QUEUE 3").performClick());
            await(()->read(()->lastText(content,"CLEAR QUEUE")!=null),10);
            main(()->lastText(content,"CLEAR QUEUE").performClick());await(()->read(()->f.controller.getMediaItemCount()==0),10);
            main(()->lastText(content,"UNDO").performClick());await(()->read(()->f.controller.getMediaItemCount()==3),10);
        }
    }
    android.view.View description(android.view.View view,String label){
        if(view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.view.View found=description(group.getChildAt(i),label);if(found!=null)return found;}return null;
    }
    @Test public void skipButtonsFollowQueueEdgesAndRepeatOrder()throws Exception{
        try(Fixture f=new Fixture()){
            android.view.View content=f.activity.findViewById(android.R.id.content);
            android.view.View previous=read(()->description(content,"Previous track")),next=read(()->description(content,"Next track"));
            await(()->read(()->!previous.isEnabled()&&!next.isEnabled()),5);queuePair(f);
            try{await(()->read(()->!previous.isEnabled()&&next.isEnabled()),5);}catch(AssertionError failure){
                throw new AssertionError(read(()->"Queue-edge state: index="+f.controller.getCurrentMediaItemIndex()+", count="+f.controller.getMediaItemCount()+", shuffle="+f.controller.getShuffleModeEnabled()+", repeat="+f.controller.getRepeatMode()+", hasPrevious="+f.controller.hasPreviousMediaItem()+", hasNext="+f.controller.hasNextMediaItem()+", previous enabled="+previous.isEnabled()+", next enabled="+next.isEnabled()),failure);
            }
            main(()->f.controller.seekTo(12000));Thread.sleep(600);assertFalse("Previous remains disabled at the first track even after playback advances",read(previous::isEnabled));
            main(next::performClick);await(()->read(()->previous.isEnabled()&&!next.isEnabled()&&f.controller.getCurrentMediaItemIndex()==1),5);
            main(previous::performClick);await(()->read(()->!previous.isEnabled()&&next.isEnabled()&&f.controller.getCurrentMediaItemIndex()==0),5);
            main(()->f.controller.setRepeatMode(Player.REPEAT_MODE_ALL));await(()->read(()->previous.isEnabled()&&next.isEnabled()),5);
            main(()->{f.controller.setRepeatMode(Player.REPEAT_MODE_OFF);f.controller.setShuffleModeEnabled(true);});
            await(()->read(()->previous.isEnabled()==f.controller.hasPreviousMediaItem()&&next.isEnabled()==f.controller.hasNextMediaItem()),5);
            main(()->f.controller.clearMediaItems());await(()->read(()->!previous.isEnabled()&&!next.isEnabled()),5);
        }
    }
    final class PagedLibrary implements AutoCloseable {
        final java.net.ServerSocket server=new java.net.ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newCachedThreadPool();
        final List<Integer> offsets=Collections.synchronizedList(new ArrayList<>());
        final List<String> terms=Collections.synchronizedList(new ArrayList<>());
        volatile boolean failNext=false,albumCatalog=false;
        PagedLibrary()throws Exception{executor.execute(()->{while(!server.isClosed())try{java.net.Socket socket=server.accept();executor.execute(()->serve(socket));}catch(Exception ignored){}});}
        void serve(java.net.Socket socket){try(socket){
            java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));String line=reader.readLine();String header;while((header=reader.readLine())!=null&&!header.isEmpty()){}
            android.net.Uri uri=android.net.Uri.parse("http://localhost"+line.split(" ")[1]);String raw=uri.getQueryParameter("StartIndex");int offset=raw==null?0:Integer.parseInt(raw);offsets.add(offset);
            boolean failed=failNext;failNext=false;org.json.JSONArray items=new org.json.JSONArray();
            String term=uri.getQueryParameter("SearchTerm");if(term!=null)terms.add(term);
            if(uri.getPath().equals("/Artists")){
                if("Overlap Artist".equals(term)&&offset==0)items.put(new org.json.JSONObject().put("Id","matching-artist").put("Name","Overlap Artist").put("Type","MusicArtist"));
            }else if(!failed){
                boolean overlap="Overlap Artist".equals(term)||uri.getQueryParameter("ArtistIds")!=null;
                int total="Overlap Artist".equals(term)&&uri.getQueryParameter("ArtistIds")==null?1:225;
                boolean album=albumCatalog&&"MusicAlbum".equals(uri.getQueryParameter("IncludeItemTypes"));
                for(int i=offset;i<Math.min(offset+100,total);i++)items.put(new org.json.JSONObject().put("Id",(album?"album-":"paged-")+i).put("Name",overlap&&i==0?"Overlap Artist":String.format(Locale.US,album?"Album %03d":"Paged %03d",i)).put("Type",album?"MusicAlbum":"Audio"));
            }
            byte[] body=new org.json.JSONObject().put("Items",items).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 "+(failed?"503 Unavailable":"200 OK")+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));socket.getOutputStream().write(body);
        }catch(Exception ignored){}}
        JellyfinApi api(){return new JellyfinApi("http://127.0.0.1:"+server.getLocalPort(),"synthetic-test-token","synthetic-user","synthetic-device");}
        @Override public void close()throws Exception{server.close();executor.shutdownNow();}
    }
    android.widget.ListView findList(android.view.View view){
        if(view instanceof android.widget.ListView list)return list;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.widget.ListView found=findList(group.getChildAt(i));if(found!=null)return found;}return null;
    }
    @Test public void libraryScrollLoadsAllPagesLazilyAndRetriesWithoutDuplicates()throws Exception{
        try(Fixture f=new Fixture();PagedLibrary library=new PagedLibrary()){
            MainActivity activity=(MainActivity)f.activity;android.view.View content=activity.findViewById(android.R.id.content);
            main(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("api");field.setAccessible(true);field.set(activity,library.api());activity.navigate("Tracks");}catch(Exception e){throw new RuntimeException(e);}});
            android.widget.ListView list=read(()->findList(content));await(()->read(()->list.getCount()==101),10);
            assertEquals(List.of(0),new ArrayList<>(library.offsets));assertNull(read(()->lastText(content,"LOAD MORE")));assertTrue("Only visible rows should be instantiated",read(()->list.getChildCount()<30));
            library.failNext=true;main(()->list.setSelection(90));await(()->library.offsets.size()==2,10);main(()->list.setSelection(100));await(()->read(()->lastText(content,"RETRY CONNECTION")!=null),10);
            assertEquals(List.of(0,100),new ArrayList<>(library.offsets));main(()->lastText(content,"RETRY CONNECTION").performClick());await(()->read(()->list.getCount()==201),10);
            assertTrue("Appending a page must retain the scroll position",read(()->list.getFirstVisiblePosition()>=85));
            main(()->list.setSelection(190));await(()->read(()->list.getCount()==225),10);
            main(()->list.setSelection(224));await(()->read(()->lastText(content,"Paged 224")!=null),5);
            assertEquals(List.of(0,100,100,200),new ArrayList<>(library.offsets));assertTrue(read(()->list.getChildCount()<30));
            for(int i=0;i<225;i++){final int index=i;assertEquals("paged-"+i,read(()->((Track)list.getAdapter().getItem(index)).id));}
            main(()->activity.openSearch("Paged"));await(()->read(()->findList(content).getCount()==101),10);main(activity::closeSearch);
            await(()->read(()->findList(content).getCount()==225&&lastText(content,"Paged 224")!=null),10);
        }
    }
    @Test public void backRestoresDeepAlbumAndSearchPositionsExactly()throws Exception{
        try(Fixture f=new Fixture();PagedLibrary library=new PagedLibrary()){
            library.albumCatalog=true;MainActivity activity=(MainActivity)f.activity;android.view.View content=activity.findViewById(android.R.id.content);
            main(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("api");field.setAccessible(true);field.set(activity,library.api());activity.navigate("Albums");}catch(Exception e){throw new RuntimeException(e);}});
            android.widget.ListView albums=read(()->findList(content));await(()->read(()->albums.getCount()==101),10);
            main(()->albums.setSelection(90));await(()->read(()->albums.getCount()==201),10);main(()->albums.setSelection(190));await(()->read(()->albums.getCount()==225),10);
            main(()->albums.setSelectionFromTop(190,-7));await(()->read(()->albums.getFirstVisiblePosition()==190&&albums.getChildAt(0).getTop()==albums.getPaddingTop()-7),5);int albumTop=read(()->albums.getChildAt(0).getTop());
            main(()->((android.view.View)lastText(content,"Album 190").getParent()).performClick());await(()->read(()->lastText(content,"Paged 000")!=null&&findList(content).getCount()==101),10);
            android.widget.ListView tracks=read(()->findList(content));main(()->tracks.setSelection(90));await(()->read(()->tracks.getCount()==201),10);main(()->tracks.setSelectionFromTop(140,-11));await(()->read(()->tracks.getFirstVisiblePosition()==140&&tracks.getChildAt(0).getTop()==tracks.getPaddingTop()-11),5);int trackTop=read(()->tracks.getChildAt(0).getTop());
            main(()->activity.openSearch("Paged"));await(()->read(()->findList(content).getCount()==101),10);int requests=library.offsets.size();
            main(()->lastText(content,"‹ BACK").performClick());try{await(()->read(()->findList(content).getFirstVisiblePosition()==140),5);}catch(AssertionError failure){throw new AssertionError(read(()->"Restored "+activity.captureBrowse()+", rows="+findList(content).getCount()),failure);}
            assertEquals(trackTop,(int)read(()->findList(content).getChildAt(0).getTop()));assertNotNull(read(()->lastText(content,"‹ BACK")));
            main(activity::onBackPressed);await(()->read(()->findList(content).getFirstVisiblePosition()==190),5);
            assertEquals(albumTop,(int)read(()->findList(content).getChildAt(0).getTop()));assertEquals(225,(int)read(()->findList(content).getCount()));assertEquals("Going back must reuse the loaded pages",requests,library.offsets.size());
            main(()->((android.view.View)lastText(content,"Album 190").getParent()).performClick());await(()->read(()->lastText(content,"Paged 000")!=null),10);main(()->lastText(content,"‹ BACK").performClick());
            await(()->read(()->findList(content).getFirstVisiblePosition()==190),5);assertEquals(albumTop,(int)read(()->findList(content).getChildAt(0).getTop()));
        }
    }
    @Test public void artistAndTitleSearchDebouncesAndPagesWithoutDuplicates()throws Exception{
        try(Fixture f=new Fixture();PagedLibrary library=new PagedLibrary()){
            MainActivity activity=(MainActivity)f.activity;android.view.View content=activity.findViewById(android.R.id.content);
            main(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("api");field.setAccessible(true);field.set(activity,library.api());activity.openSearch("");}catch(Exception e){throw new RuntimeException(e);}});
            main(()->{android.widget.EditText input=findInput(content);input.setText("O");input.setText("Over");input.setText("Overlap Artist");});
            android.widget.ListView list=read(()->findList(content));await(()->read(()->list.getCount()==101),10);
            assertEquals(List.of("Overlap Artist","Overlap Artist"),new ArrayList<>(library.terms));
            assertNotNull(read(()->lastText(content,"Overlap Artist")));assertNull(read(()->lastText(content,"GO")));
            main(()->list.setSelection(90));await(()->read(()->list.getCount()==201),10);
            main(()->list.setSelection(190));await(()->read(()->list.getCount()==225),10);
            Set<String> ids=new HashSet<>();for(int i=0;i<225;i++){final int at=i;assertTrue("A title/artist overlap must appear only once",ids.add(read(()->((Track)list.getAdapter().getItem(at)).id)));}
            assertEquals(225,ids.size());assertEquals("paged-224",read(()->((Track)list.getAdapter().getItem(224)).id));
        }
    }
    boolean titleHasColor(android.widget.TextView view,int color){
        return read(()->{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
            boolean found=false;for(int y=0;y<bitmap.getHeight()&&!found;y++)for(int x=0;x<bitmap.getWidth();x++)if(bitmap.getPixel(x,y)==color){found=true;break;}bitmap.recycle();return found;});
    }
    @Test public void longTitleReachesItsEndAndLoopsDuringPlaybackUpdates()throws Exception{
        try(Fixture f=new Fixture()){
            Track track=f.api.items("Audio","",null,0).get(0);String name="BEGIN A deliberately very long track title with its final word END";
            f.play(new Track(track.id,name,track.artist,track.album,"Audio",track.entryId,track.duration,track.imageId,track.imageTag,track.favorite));
            android.view.View content=f.activity.findViewById(android.R.id.content);await(()->read(()->lastText(content,name)!=null),5);
            android.widget.TextView title=read(()->(android.widget.TextView)lastText(content,name));int head=0xFF6699FF,tail=Retro.GOLD;
            assertTrue("The fixture title must exceed the visible title area",read(()->title.getPaint().measureText(name)>title.getWidth()));
            // Coloured end markers let the rendered viewport prove that the hidden suffix
            // becomes visible, then the complete title passes and loops back to the prefix.
            main(()->{android.text.SpannableString marked=new android.text.SpannableString(name);marked.setSpan(new android.text.style.ForegroundColorSpan(head),0,5,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);marked.setSpan(new android.text.style.ForegroundColorSpan(tail),name.length()-3,name.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);title.setText(marked);});
            assertTrue(titleHasColor(title,head));assertFalse(titleHasColor(title,tail));
            await(()->titleHasColor(title,tail),25);
            await(()->!titleHasColor(title,tail)&&titleHasColor(title,head),25);
            assertEquals(name,read(()->title.getText().toString()));assertTrue(read(()->f.controller.getMediaItemCount()==1));
        }
    }
    Retro.Spectrum findSpectrum(android.view.View view){
        if(view instanceof Retro.Spectrum spectrum)return spectrum;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){Retro.Spectrum spectrum=findSpectrum(group.getChildAt(i));if(spectrum!=null)return spectrum;}
        return null;
    }
    android.graphics.Bitmap drawSpectrum(Retro.Spectrum spectrum){
        return read(()->{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(spectrum.getWidth(),spectrum.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);spectrum.draw(new android.graphics.Canvas(bitmap));return bitmap;});
    }
    @Test public void spectrumAnimatesOnlyDuringActualPlayback()throws Exception{
        try(Fixture f=new Fixture()){
            Track track=f.api.items("Audio","",null,0).get(0);f.play(track);
            Retro.Spectrum spectrum=read(()->findSpectrum(f.activity.findViewById(android.R.id.content)));assertNotNull(spectrum);
            await(()->read(()->{for(float bar:spectrum.bars)if(bar>.05f)return true;return false;}),5);
            main(()->f.controller.pause());
            await(()->read(()->{for(float bar:spectrum.bars)if(bar!=0)return false;for(float peak:spectrum.peaks)if(peak!=0)return false;return !AudioDsp.playing;}),5);
            android.graphics.Bitmap paused=drawSpectrum(spectrum);
            // Buffered/decoded samples must not animate an inactive view, even if a producer's
            // playback flag hasn't yet caught up with the controller's state.
            main(()->{AudioDsp.playing=true;float[] noise=new float[28];java.util.Arrays.fill(noise,1);AudioDsp.spectrum=noise;spectrum.setPlaybackActive(false);});
            Thread.sleep(150);assertTrue("Paused display must remain completely still",paused.sameAs(drawSpectrum(spectrum)));
            main(()->{AudioDsp.playing=false;f.controller.play();});
            await(()->read(()->{for(float bar:spectrum.bars)if(bar>.05f)return true;return false;}),5);
            main(()->f.controller.stop());
            await(()->read(()->{for(float bar:spectrum.bars)if(bar!=0)return false;return !AudioDsp.playing;}),5);
            android.graphics.Bitmap stopped=drawSpectrum(spectrum);Thread.sleep(150);assertTrue(stopped.sameAs(drawSpectrum(spectrum)));
        }
    }
    android.widget.EditText findInput(android.view.View view){
        if(view instanceof android.widget.EditText input)return input;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){android.widget.EditText input=findInput(group.getChildAt(i));if(input!=null)return input;}
        return null;
    }
    @Test public void dedicatedSearchStaysAboveKeyboardAndAddsWithoutLeaving()throws Exception{
        try(Fixture f=new Fixture()){
            android.view.View content=f.activity.findViewById(android.R.id.content);
            main(()->findInput(content).performClick());
            await(()->read(()->lastText(content,"‹ BACK")!=null),5);
            await(()->read(()->content.getRootWindowInsets()!=null&&content.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime())),5);
            android.widget.EditText input=read(()->findInput(content));assertTrue(read(input::hasFocus));
            instrumentation.sendStringSync("Signal");await(()->read(()->lastText(content,"Test Signal One")!=null&&lastText(content,"Test Signal Two")!=null),10);
            assertTrue("The entire search input must be visible with the keyboard open",read(()->{android.graphics.Rect visible=new android.graphics.Rect();return input.getGlobalVisibleRect(visible)&&visible.height()==input.getHeight();}));
            main(()->((android.view.View)lastText(content,"Test Signal One").getParent()).performClick());main(()->((android.view.View)lastText(content,"Test Signal Two").getParent()).performClick());
            await(()->read(()->f.controller.getMediaItemCount()==2),5);assertTrue(read(()->input.hasFocus()&&content.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime())));
            main(()->input.setText("Two"));await(()->read(()->lastText(content,"Test Signal Two")!=null&&lastText(content,"Test Signal One")==null),10);
            main(()->input.setText("rEwInDaMp tEsT"));await(()->read(()->lastText(content,"Test Signal One")!=null&&lastText(content,"Test Signal Two")!=null),10);
            assertEquals("Artist-name search should find tracks whose titles do not include the artist",2,(int)read(()->findList(content).getCount()));
            main(()->lastText(content,"‹ BACK").performClick());await(()->read(()->lastText(content,"‹ BACK")==null),5);assertEquals(2,(int)read(()->f.controller.getMediaItemCount()));
        }
    }
    void queuePair(Fixture f)throws Exception{
        List<Track> tracks=f.api.items("Audio","",null,0);main(()->{f.controller.setRepeatMode(Player.REPEAT_MODE_OFF);f.controller.setShuffleModeEnabled(false);f.controller.setMediaItems(List.of(f.offline.media(tracks.get(0)),f.offline.media(tracks.get(1))));f.controller.setVolume(.65f);f.controller.prepare();f.controller.play();});
        await(()->read(()->f.controller.isPlaying()&&f.controller.getDuration()>1000),15);
    }
    @Test public void crossfadeOverlapsAndKeepsQueueVolumeAndPauseBehavior()throws Exception{
        try(Fixture f=new Fixture()){
            main(()->TransitionSettings.save(context,TransitionSettings.CROSSFADE,2));queuePair(f);
            long duration=read(()->f.controller.getDuration());main(()->f.controller.seekTo(duration-5000));
            await(()->read(()->f.controller.getSessionExtras().getBoolean("crossfading")),8);
            assertEquals(1,(int)read(()->f.controller.getCurrentMediaItemIndex()));assertEquals(2,(int)read(()->f.controller.getMediaItemCount()));
            await(()->read(()->f.controller.getVolume()>.05f&&f.controller.getVolume()<.6f),2);
            assertEquals(.65f,(float)read(()->f.controller.getSessionExtras().getFloat("logical_volume")),.01f);
            assertEquals(.65f,QueueStore.load(f.offline.store).volume(),.01f);
            main(()->{int at=f.controller.getCurrentMediaItemIndex();MediaItem item=f.controller.getCurrentMediaItem();f.controller.replaceMediaItem(at,item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle("Updated artwork metadata").build()).build());});Thread.sleep(150);
            assertTrue("Artwork/metadata updates must not cut an active fade short",read(()->f.controller.getSessionExtras().getBoolean("crossfading")));
            await(()->read(()->!f.controller.getSessionExtras().getBoolean("crossfading")&&f.controller.getCurrentPosition()>=1800),5);
            assertEquals(.65f,(float)read(()->f.controller.getVolume()),.02f);
            // A second overlap checks that pause stops both audio players, including the old tail.
            Track next=f.api.items("Audio","",null,0).get(0);main(()->f.controller.addMediaItem(f.offline.media(next)));long second=read(()->f.controller.getDuration());main(()->f.controller.seekTo(second-4000));
            await(()->read(()->f.controller.getSessionExtras().getBoolean("crossfading")),7);main(()->f.controller.pause());
            await(()->read(()->!f.controller.getSessionExtras().getBoolean("crossfading")&&!AudioDsp.playing),5);Thread.sleep(500);
            assertFalse("Pausing a crossfade must not leave an outgoing player audible",((android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).isMusicActive());
            assertFalse(read(()->f.controller.getPlayWhenReady()));assertEquals(3,(int)read(()->f.controller.getMediaItemCount()));
        }finally{main(()->TransitionSettings.save(context,TransitionSettings.GAPLESS,5));}
    }
    @Test public void gaplessAdvancesWithoutAnArtificialPause()throws Exception{
        try(Fixture f=new Fixture()){
            main(()->TransitionSettings.save(context,TransitionSettings.GAPLESS,5));List<Track> tracks=f.api.items("Audio","",null,0);main(()->{try{f.offline.enqueue(tracks);}catch(Exception e){throw new RuntimeException(e);}});await(()->f.offline.complete(tracks.get(0).id)&&f.offline.complete(tracks.get(1).id),30);queuePair(f);
            java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean(false),paused=new java.util.concurrent.atomic.AtomicBoolean(false);
            main(()->f.controller.addListener(new Player.Listener(){@Override public void onPlayWhenReadyChanged(boolean ready,int reason){if(armed.get()&&!ready)paused.set(true);}}));long duration=read(()->f.controller.getDuration());main(()->f.controller.seekTo(duration-1000));await(()->read(()->f.controller.isPlaying()),5);armed.set(true);
            await(()->read(()->f.controller.getCurrentMediaItemIndex()==1&&f.controller.isPlaying()),5);assertFalse("Gapless must not insert a deliberate pause",paused.get());assertFalse(read(()->f.controller.getSessionExtras().getBoolean("crossfading")));
        }
    }
    @Test public void separateTracksAddsShortPauseAndDoesNotSkipNextTrack()throws Exception{
        try(Fixture f=new Fixture()){
            main(()->TransitionSettings.save(context,TransitionSettings.SEPARATE,5));queuePair(f);java.util.concurrent.atomic.AtomicLong pausedAt=new java.util.concurrent.atomic.AtomicLong(),resumedAt=new java.util.concurrent.atomic.AtomicLong();
            main(()->f.controller.addListener(new Player.Listener(){@Override public void onPlayWhenReadyChanged(boolean ready,int reason){if(!ready&&reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)pausedAt.set(android.os.SystemClock.elapsedRealtime());if(ready&&pausedAt.get()>0)resumedAt.set(android.os.SystemClock.elapsedRealtime());}}));
            long duration=read(()->f.controller.getDuration());main(()->f.controller.seekTo(duration-700));await(()->read(()->f.controller.getCurrentMediaItemIndex()==1&&f.controller.isPlaying()),5);
            assertTrue("Separate mode must pause at the boundary",pausedAt.get()>0);assertTrue(resumedAt.get()-pausedAt.get()>=200);assertEquals(2,(int)read(()->f.controller.getMediaItemCount()));
        }finally{main(()->TransitionSettings.save(context,TransitionSettings.GAPLESS,5));}
    }
    @Test public void additiveQueueSurvivesServiceRestartAndKeepsPosition()throws Exception{
        try(Fixture f=new Fixture()){
            List<Track> tracks=f.api.items("Audio","",null,0);
            main(()->QueueStore.append(f.controller,List.of(f.offline.media(tracks.get(0)))));
            await(()->read(()->f.controller.getCurrentPosition()>400),15);long before=read(()->f.controller.getCurrentPosition());
            main(()->QueueStore.append(f.controller,List.of(f.offline.media(tracks.get(1)),f.offline.media(tracks.get(0)))));
            assertEquals(3,(int)read(()->f.controller.getMediaItemCount()));assertEquals(tracks.get(0).id,read(()->f.controller.getCurrentMediaItem().mediaId));assertTrue("Appending must preserve playback position (controller estimates may drift slightly)",read(()->f.controller.getCurrentPosition())>=before-200);
            main(()->{f.controller.pause();f.controller.seekTo(1,12345);f.controller.setShuffleModeEnabled(true);f.controller.setRepeatMode(Player.REPEAT_MODE_ALL);f.controller.setVolume(.6f);});
            Thread.sleep(300);f.stop();QueueStore.Snapshot saved=QueueStore.load(f.offline.store);assertEquals(3,saved.tracks().size());assertEquals(1,saved.index());assertTrue(saved.position()>=12000);assertTrue(saved.shuffle());assertEquals(Player.REPEAT_MODE_ALL,saved.repeat());
            f.start();assertEquals(3,(int)read(()->f.controller.getMediaItemCount()));assertEquals(1,(int)read(()->f.controller.getCurrentMediaItemIndex()));assertTrue(read(()->f.controller.getCurrentPosition())>=12000);assertFalse(read(()->f.controller.getPlayWhenReady()));
            main(()->QueueStore.append(f.controller,List.of(f.offline.media(tracks.get(0)))));assertEquals(4,(int)read(()->f.controller.getMediaItemCount()));assertFalse("Adding to a paused queue must not resume it",read(()->f.controller.getPlayWhenReady()));
        }
    }
    @Test public void offlineDownloadIsPlayableWithoutAnUpstreamAndHasArtwork()throws Exception{
        try(Fixture f=new Fixture()){
            List<Track> tracks=f.api.items("Audio","",null,0);byte[] art=f.api.image(tracks.get(0));assertTrue("Test album artwork must be available",art.length>0);f.offline.store.artwork(tracks.get(0),art);
            main(()->{try{f.offline.enqueue(tracks);}catch(Exception e){throw new RuntimeException(e);}});
            await(()->f.offline.complete(tracks.get(0).id)&&f.offline.complete(tracks.get(1).id),45);
            for(Track track:tracks){
                Download download=f.offline.download(track.id);assertEquals(Download.STATE_COMPLETED,download.state);
                var source=new CacheDataSource.Factory().setCache(f.offline.cache).setUpstreamDataSourceFactory(null).createDataSource();long count=0;
                try{source.open(new DataSpec.Builder().setUri(download.request.uri).setKey(track.id).build());byte[] buffer=new byte[8192];int n;while((n=source.read(buffer,0,buffer.length))!=-1)count+=n;}finally{source.close();}
                assertTrue("Entire file must be readable from cache with networking impossible",count>10000);
                assertTrue(f.offline.media(track).mediaMetadata.extras.getBoolean("offline"));
            }
            assertNotNull(f.offline.media(tracks.get(0)).mediaMetadata.artworkData);f.play(tracks.get(0));
            // Cached playback and seeks use the same source/cache key as the offline-only read above.
            main(()->f.controller.seekTo(20000));Thread.sleep(300);assertTrue(read(()->f.controller.getCurrentPosition())>=20000);
        }
    }
    @Test public void favouritesHistoryAndPlaylistUndoSyncToJellyfin()throws Exception{
        try(Fixture f=new Fixture()){
            Track track=f.api.items("Audio","",null,0).get(0);f.offline.store.editFavorite(track,true);new PlaybackReporter(f.api,f.offline.store).sync();assertTrue(f.api.item(track.id).favorite);assertTrue(f.api.browse("Favourites","",null,"",0).stream().anyMatch(t->t.id.equals(track.id)));
            String name="Advanced "+System.nanoTime();f.api.create(name,f.api.items("Audio","",null,0));String id=f.api.items("Playlist",name,null,0).get(0).id;
            try{Track removed=f.api.playlistItems(id).get(0);f.api.remove(id,removed);f.api.restoreEntry(id,removed,0);assertEquals(removed.id,f.api.playlistItems(id).get(0).id);
                String copy=name+" copy";f.api.create(copy,f.api.playlistItems(id));String copyId=f.api.items("Playlist",copy,null,0).get(0).id;try{assertEquals(2,f.api.playlistItems(copyId).size());}finally{f.api.request("DELETE","/Items/"+copyId,null);}
            }finally{f.api.request("DELETE","/Items/"+id,null);}
            f.play(track);main(()->f.controller.seekTo(44000));Thread.sleep(300);main(()->{f.controller.pause();f.controller.clearMediaItems();});
            await(()->{try{new PlaybackReporter(f.api,f.offline.store).sync();return f.api.request("GET","/Users/"+f.api.userId+"/Items/"+track.id,null).getJSONObject("UserData").optBoolean("Played");}catch(Exception e){return false;}},15);
            assertTrue(f.api.browse("History","",null,"",0).stream().anyMatch(t->t.id.equals(track.id)));f.api.favorite(track,false);
        }
    }
    @Test public void legacyCarBrowserBrowsesAndVoiceSelectionAppends()throws Exception{
        try(Fixture f=new Fixture()){
            List<Track> tracks=f.api.items("Audio","",null,0);main(()->QueueStore.append(f.controller,List.of(f.offline.media(tracks.get(0)))));
            CountDownLatch connected=new CountDownLatch(1),children=new CountDownLatch(1);AtomicReference<MediaBrowser> browser=new AtomicReference<>();AtomicReference<List<MediaBrowser.MediaItem>> items=new AtomicReference<>();
            main(()->{MediaBrowser b=new MediaBrowser(context,new ComponentName(context,PlaybackService.class),new MediaBrowser.ConnectionCallback(){@Override public void onConnected(){connected.countDown();}},null);browser.set(b);b.connect();});assertTrue(connected.await(10,TimeUnit.SECONDS));
            main(()->browser.get().subscribe("Tracks",new MediaBrowser.SubscriptionCallback(){@Override public void onChildrenLoaded(String id,List<MediaBrowser.MediaItem> result){items.set(result);children.countDown();}}));assertTrue(children.await(10,TimeUnit.SECONDS));assertEquals(2,items.get().size());assertTrue(items.get().get(0).isPlayable());
            main(()->new android.media.session.MediaController(context,browser.get().getSessionToken()).getTransportControls().playFromMediaId(tracks.get(1).id,null));
            await(()->read(()->f.controller.getMediaItemCount()==2&&f.controller.getCurrentMediaItemIndex()==1),10);assertEquals(tracks.get(0).id,read(()->f.controller.getMediaItemAt(0).mediaId));
            main(()->new android.media.session.MediaController(context,browser.get().getSessionToken()).getTransportControls().playFromSearch("Test Signal One",null));
            await(()->read(()->f.controller.getMediaItemCount()==3),10);main(()->browser.get().disconnect());
        }
    }
    @Test public void tenBandEqualizerChangesFrequencyResponseAndSpectrumReflectsAudio()throws Exception{
        try{
            float[] gains=new float[10];gains[0]=12;DspSettings.set(context,true,0,gains);
            double bass=processedRms(70),mid=processedRms(1000);assertTrue("Bass band must affect real PCM, with headroom",bass>mid*2.5);
            float maximum=0;for(float bar:AudioDsp.spectrum)maximum=Math.max(maximum,bar);assertTrue("Spectrum must be generated from PCM",maximum>.1);
            DspSettings.set(context,false,0,new float[10]);double bypass=processedRms(1000);assertTrue("Disabled EQ must be a transparent bypass",bypass>.065&&bypass<.075);
            AudioDsp silent=new AudioDsp();silent.configure(new AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));silent.flush();ByteBuffer zero=ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder());zero.limit(8192);silent.queueInput(zero);silent.getOutput();for(float bar:AudioDsp.spectrum)assertEquals(0,bar,.0001);
        }finally{DspSettings.set(context,false,0,new float[10]);}
    }
    double processedRms(double frequency)throws Exception{
        AudioDsp dsp=new AudioDsp();dsp.configure(new AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));dsp.flush();ByteBuffer input=ByteBuffer.allocateDirect(48000*4).order(ByteOrder.nativeOrder());
        for(int i=0;i<48000;i++){short sample=(short)(3276*Math.sin(2*Math.PI*frequency*i/48000));input.putShort(sample).putShort(sample);}input.flip();dsp.queueInput(input);ByteBuffer output=dsp.getOutput().order(ByteOrder.nativeOrder());double sum=0;int n=0;while(output.remaining()>=2){double value=output.getShort()/32768.0;if(n++>48000)sum+=value*value;}return Math.sqrt(sum/(n-48001));
    }
}
