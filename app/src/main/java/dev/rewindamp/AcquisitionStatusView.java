package dev.rewindamp;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account-scoped acquisition status. Never overlays search or starts acquisitions. */
final class AcquisitionStatusView extends TextView {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private boolean attached,busy;
    private final Runnable refresh=this::refresh;
    AcquisitionStatusView(Context context){
        super(context);setVisibility(GONE);setTextSize(11);setTextColor(Retro.GOLD);setTypeface(Retro.MONO);
        setGravity(Gravity.CENTER_VERTICAL);setPadding(Retro.dp(context,10),0,Retro.dp(context,10),0);
        setMinHeight(Retro.dp(context,48));setSingleLine();setEllipsize(TextUtils.TruncateAt.MARQUEE);setMarqueeRepeatLimit(-1);setSelected(true);
        setBackgroundColor(Retro.PANEL);setContentDescription("Open library request activity");
        setOnClickListener(v->context.startActivity(new Intent(context,MixarrActivity.class).putExtra("activity",true)));
        setText("Library activity  ›");
    }
    static String summary(JSONArray history){
        int pending=0,failed=0;String title="",stage="";
        for(int i=0;i<history.length();i++){
            JSONObject row=history.optJSONObject(i);if(row==null)continue;
            JSONArray items=row.has("import_id")?row.optJSONArray("items"):new JSONArray().put(row);
            if(items==null)continue;
            for(int j=0;j<items.length();j++){JSONObject item=items.optJSONObject(j);if(item==null)continue;
                String state=item.optString("state");if(MixarrActivity.active(state)){pending++;if(title.isEmpty()){title=item.optString("title",row.optString("title"));JSONObject progress=item.optJSONObject("progress");stage=progress==null?state:progress.optString("label",state);if(progress!=null&&progress.has("percent"))stage+=" "+progress.optInt("percent")+"%";}}
                else if(state.equals("failed")||state.equals("cancelled"))failed++;
            }
        }
        if(pending>0)return pending+" active · "+title+" · "+stage+(failed>0?" · "+failed+" need attention":"")+"  ›";
        return failed>0?failed+" requests need attention  ›":history.length()>0?"Library activity · All requests finished  ›":"Library activity · No requests yet  ›";
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;io=Executors.newSingleThreadExecutor();handler.post(refresh);}
    @Override protected void onDetachedFromWindow(){attached=false;handler.removeCallbacks(refresh);if(io!=null)io.shutdown();super.onDetachedFromWindow();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);handler.removeCallbacks(refresh);if(focused&&attached)handler.post(refresh);}
    private void refresh(){
        if(!attached||!hasWindowFocus()||busy)return;
        JellyfinApi jellyfin=SessionStore.load(getContext());if(jellyfin==null){setVisibility(GONE);return;}
        final String scope;try{scope=MixarrApi.scope(jellyfin);}catch(Exception e){return;}
        MixarrApi api=MixarrApi.load(getContext(),scope);if(api==null){setVisibility(GONE);return;}
        setVisibility(VISIBLE);setText(summary(api.history(getContext(),scope)));busy=true;
        io.execute(()->{try{
            JSONArray history=api.history(getContext(),scope);
            for(int i=0;i<history.length();i++){JSONObject row=history.getJSONObject(i);if(!MixarrActivity.pending(row))continue;
                JSONObject next;
                if(row.has("import_id")){next=(JSONObject)api.request("/api/v1/youtube-imports/"+android.net.Uri.encode(row.getString("import_id")),null);next.put("import_id",row.getString("import_id"));if(row.has("skipped"))next.put("skipped",row.getInt("skipped"));}
                else{if(row.isNull("job_id"))continue;next=(JSONObject)api.request("/api/v1/track-requests/jobs/"+row.getLong("job_id"),null);next.put("title",row.optString("title")).put("artist",row.optString("artist"));}
                MixarrApi.remember(getContext(),scope,api,next);
            }
        }catch(Exception ignored){/* Saved state stays visible; try again while foregrounded. */}
            handler.post(()->{busy=false;if(!attached)return;setText(summary(api.history(getContext(),scope)));if(hasWindowFocus())handler.postDelayed(refresh,5000);});
        });
    }
}
