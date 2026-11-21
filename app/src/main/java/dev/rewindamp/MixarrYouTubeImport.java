package dev.rewindamp;

import android.text.InputFilter;
import android.text.InputType;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** In-memory preview only: signed selections and source references are never persisted. */
final class MixarrYouTubeImport {
    private final MixarrActivity activity;
    private final JSONObject preview;
    private final LinearLayout rows;
    private final List<Row> entries=new ArrayList<>();
    private final TextView error;
    private final boolean playlist;
    private static final class Row {
        final JSONObject source;
        final CheckBox selected;
        final EditText title,artist,album,start,end;
        Row(JSONObject source,CheckBox selected,EditText title,EditText artist,EditText album,EditText start,EditText end){this.source=source;this.selected=selected;this.title=title;this.artist=artist;this.album=album;this.start=start;this.end=end;}
    }
    MixarrYouTubeImport(MixarrActivity activity,JSONObject preview,LinearLayout box,Consumer<JSONObject> submit){
        this.activity=activity;this.preview=preview;playlist="playlist".equals(preview.optString("mode"));
        box.addView(activity.text(preview.optString("title"),16,Retro.GREEN));
        box.addView(activity.text("Import audio to the server library. Review the uploader's names. Times are seconds from the start of the video; chapters become separate tracks. Existing audio is kept.",12,Retro.MUTED));
        if(preview.optInt("skipped")>0)box.addView(activity.text(preview.optInt("skipped")+" unavailable entries skipped.",12,Retro.GOLD));
        EditText artist=field(box,"Artist for all tracks","",false),album=field(box,"Album for all tracks",preview.optString("title"),false);
        activity.addButton(box,"APPLY ARTIST TO ALL",()->{for(Row row:entries)row.artist.setText(artist.getText());});
        activity.addButton(box,"APPLY ALBUM TO ALL",()->{for(Row row:entries)row.album.setText(album.getText());});
        rows=activity.column();box.addView(rows);
        JSONArray items=preview.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++)add(items.optJSONObject(i));
        if(!playlist)activity.addButton(box,"ADD ANOTHER CHAPTER",()->{
            if(entries.isEmpty()||entries.size()>=100)return;
            try{JSONObject source=new JSONObject(entries.get(0).source.toString());source.put("title","").put("start",0).put("end",source.opt("duration"));
                Row previous=entries.get(entries.size()-1);String start=previous.end.getText().toString();add(source);entries.get(entries.size()-1).start.setText(start);
            }catch(Exception ignored){}
        });
        error=activity.text("Choose tracks, then confirm to start downloading.",12,Retro.GOLD);box.addView(error);
        activity.addButton(box,"IMPORT SELECTED AUDIO",()->{
            try{submit.accept(selection());error.setText("Import requested. If it fails, confirm again to retry.");}
            catch(Exception e){error.setText("Choose at least one track. Fill in titles, artists and finite start/end times inside the video, without overlaps.");}
        });
    }
    private EditText field(LinearLayout box,String label,String value,boolean number){
        box.addView(activity.text(label,12,Retro.MUTED));EditText input=activity.field(label,false);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(number?20:300)});
        if(number)input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value);box.addView(input,new LinearLayout.LayoutParams(-1,activity.dp(48)));return input;
    }
    private void add(JSONObject source){
        if(source==null)return;
        LinearLayout box=activity.column();box.setPadding(activity.dp(8),activity.dp(12),activity.dp(8),activity.dp(12));box.setBackground(Retro.bevel(Retro.LCD,true));rows.addView(box);
        int index=entries.size()+1;CheckBox selected=new CheckBox(activity);selected.setText("Import track "+index);selected.setTextColor(Retro.GREEN);selected.setChecked(true);box.addView(selected);
        EditText title=field(box,"Track "+index+" title",source.optString("title"),false),artist=field(box,"Track "+index+" artist",source.optString("artist"),false),album=field(box,"Track "+index+" album",source.isNull("album")?"":source.optString("album"),false);
        EditText start=null,end=null;
        if(!playlist){start=field(box,"Track "+index+" start (seconds)",source.optString("start","0"),true);end=field(box,"Track "+index+" end (seconds)",source.isNull("end")?"":source.optString("end"),true);}
        entries.add(new Row(source,selected,title,artist,album,start,end));
    }
    private JSONObject selection()throws Exception {
        List<YouTubeSelection.Chapter> chapters=new ArrayList<>();
        for(Row row:entries)if(row.selected.isChecked())chapters.add(new YouTubeSelection.Chapter(row.source,
            row.title.getText().toString(),row.artist.getText().toString(),row.album.getText().toString(),
            playlist?"":row.start.getText().toString(),playlist?"":row.end.getText().toString()));
        return YouTubeSelection.body(preview,chapters);
    }
}
