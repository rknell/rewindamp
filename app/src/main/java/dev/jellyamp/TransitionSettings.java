package dev.jellyamp;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.*;

final class TransitionSettings {
    static final String PREFS="playback-transitions",GAPLESS="gapless",CROSSFADE="crossfade",SEPARATE="separate";
    record Values(String mode,int seconds){}
    static Values load(Context context){var p=context.getSharedPreferences(PREFS,0);String mode=p.getString("mode",GAPLESS);if(!mode.equals(CROSSFADE)&&!mode.equals(SEPARATE))mode=GAPLESS;return new Values(mode,Math.max(1,Math.min(12,p.getInt("seconds",5))));}
    static void save(Context context,String mode,int seconds){context.getSharedPreferences(PREFS,0).edit().putString("mode",mode).putInt("seconds",Math.max(1,Math.min(12,seconds))).apply();}
    static void show(Context context){
        Values initial=load(context);LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*context.getResources().getDisplayMetrics().density);box.setPadding(pad,0,pad,pad);
        RadioGroup modes=new RadioGroup(context);String[] values={GAPLESS,CROSSFADE,SEPARATE},labels={"Gapless — continuous albums","Crossfade — overlap songs","Separate tracks — short pause"};
        for(int i=0;i<values.length;i++){RadioButton option=new RadioButton(context);option.setId(android.view.View.generateViewId());option.setTag(values[i]);option.setText(labels[i]);modes.addView(option);if(values[i].equals(initial.mode()))modes.check(option.getId());}box.addView(modes);
        TextView duration=new TextView(context);duration.setText("Crossfade: "+initial.seconds()+" seconds");box.addView(duration);SeekBar seconds=new SeekBar(context);seconds.setMax(11);seconds.setProgress(initial.seconds()-1);seconds.setContentDescription("Crossfade duration in seconds");seconds.setEnabled(initial.mode().equals(CROSSFADE));box.addView(seconds);
        seconds.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){duration.setText("Crossfade: "+(progress+1)+" seconds");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        modes.setOnCheckedChangeListener((group,id)->seconds.setEnabled(CROSSFADE.equals(group.findViewById(id).getTag())));TextView note=new TextView(context);note.setText("Gapless preserves album transitions. Crossfade blends the end of one song into the next when it is ready. Separate tracks adds a 250 ms pause.");box.addView(note);
        new AlertDialog.Builder(context).setTitle("Playback transitions").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->save(context,(String)modes.findViewById(modes.getCheckedRadioButtonId()).getTag(),seconds.getProgress()+1)).show();
    }
}
