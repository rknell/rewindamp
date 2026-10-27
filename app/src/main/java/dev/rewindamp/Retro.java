package dev.rewindamp;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.SeekBar;
import java.util.function.Consumer;

/** Drawn at native resolution: no screenshot backgrounds or scaled bitmap controls. */
final class Retro {
    static final int BG=0xFF171723,PANEL=0xFF29293D,GREEN=0xFF25F11B,MUTED=0xFF9DA5B2,WHITE=0xFFE1E4E7,LCD=0xFF030604,GOLD=0xFFCEBE7A;
    static final Typeface MONO=Typeface.create("monospace",Typeface.NORMAL);
    static Drawable bevel(int color,boolean inset){return new Drawable(){final Paint p=new Paint();
        @Override public void draw(Canvas c){Rect b=getBounds();p.setColor(color);c.drawRect(b,p);
            p.setColor(inset?0xFF080812:0xFF77798B);c.drawRect(b.left,b.top,b.right,b.top+2,p);c.drawRect(b.left,b.top,b.left+2,b.bottom,p);
            p.setColor(inset?0xFF5C6074:0xFF0A0B13);c.drawRect(b.left,b.bottom-2,b.right,b.bottom,p);c.drawRect(b.right-2,b.top,b.right,b.bottom,p);
            if(!inset){p.setColor(0xFF3E4055);c.drawRect(b.left+2,b.top+2,b.right-2,b.top+4,p);}}
        @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}};}
    static Drawable thumb(Context context){float density=context.getResources().getDisplayMetrics().density;return new Drawable(){final Paint p=new Paint();@Override public void draw(Canvas c){Rect b=getBounds();p.setColor(0xFF7C6939);c.drawRect(b,p);p.setColor(0xFFF4E8A9);c.drawRect(b.left,b.top,b.right,b.top+2,p);c.drawRect(b.left,b.top,b.left+2,b.bottom,p);p.setColor(GOLD);c.drawRect(b.left+2,b.top+2,b.right-2,b.bottom-2,p);p.setColor(0xFF8D7C4E);c.drawRect(b.left+3,b.centerY()-1,b.right-3,b.centerY()+1,p);}@Override public int getIntrinsicWidth(){return Math.round(18*density);}@Override public int getIntrinsicHeight(){return Math.round(9*density);}@Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}};}
    static final class Rail extends View {
        final Paint p=new Paint(3);final String title;
        Rail(Context context,String title){super(context);this.title=title;setContentDescription(title);}
        @Override protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight();
            c.drawColor(PANEL);p.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));p.setTextSize(11*d);float tw=p.measureText(title),x=(w-tw)/2;
            rail(c,6*d,x-8*d,h,d);rail(c,x+tw+8*d,w-6*d,h,d);p.setColor(WHITE);c.drawText(title,x,h/2+4*d,p);
        }
        void rail(Canvas c,float left,float right,float h,float d){if(right<left)return;p.setColor(0xFF8F8051);c.drawRect(left,h/2-3*d,right,h/2+3*d,p);p.setColor(0xFFF6EFC2);c.drawRect(left,h/2-3*d,right,h/2-1*d,p);p.setColor(0xFFD4C583);c.drawRect(left,h/2,right,h/2+d,p);}
    }
    static android.widget.LinearLayout navigationBar(android.app.Activity activity,boolean podcasts){
        android.widget.LinearLayout bar=new android.widget.LinearLayout(activity);
        bar.setBackground(bevel(PANEL,false));
        float density=activity.getResources().getDisplayMetrics().density;
        for(boolean tab:new boolean[]{false,true}){
            MetalButton button=new MetalButton(activity,tab?"PODCASTS":"MUSIC");button.setSelected(tab==podcasts);
            button.setTextColor(tab==podcasts?0xFF0C5615:0xFF283A46);
            button.setContentDescription((tab?"Podcasts":"Music")+(tab==podcasts?", selected":""));
            button.setOnClickListener(view->{if(tab==podcasts){if(tab&&activity instanceof PodcastDiscoveryActivity)activity.finish();return;}
                if(tab)activity.startActivity(new android.content.Intent(activity,PodcastActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                else{activity.startActivity(new android.content.Intent(activity,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));activity.finish();}
            });
            bar.addView(button,new android.widget.LinearLayout.LayoutParams(0,Math.round(48*density),1));
        }
        return bar;
    }
    static MetalButton settingsButton(Context context,Runnable action){
        MetalButton button=new MetalButton(context,"").icon("settings");
        button.setOnClickListener(view->action.run());
        button.setTooltipText("Settings");
        return button;
    }
    static final class MetalButton extends Button {
        final Paint p=new Paint(3);String symbol="";
        MetalButton(Context c,String label){super(c);setAllCaps(false);setText(label);setTextSize(10);setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));setTextColor(0xFF283A46);setGravity(android.view.Gravity.CENTER);setPadding(2,0,2,0);setMinimumWidth(0);setMinWidth(0);setMinimumHeight(0);setMinHeight(0);setBackground(null);}
        MetalButton icon(String value){if(symbol.equals(value))return this;symbol=value;invalidate();setText("");setContentDescription(switch(value){case "previous"->"Previous track";case "next"->"Next track";case "play"->"Play";case "pause"->"Pause";case "stop"->"Stop";case "settings"->"Settings";case "back15"->"Jump back 15 seconds";case "forward30"->"Jump forward 30 seconds";case "edit"->"Edit subscription";default->"Browse music";});return this;}
        @Override protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density;float l=2*d,t=Math.max(2*d,(getHeight()-30*d)/2),r=getWidth()-2*d,b=getHeight()-t;
            p.setShader(new LinearGradient(0,t,0,b,new int[]{0xFFF1F5F5,0xFFA9BBC5,0xFFCCD7DC},null,Shader.TileMode.CLAMP));c.drawRect(l,t,r,b,p);p.setShader(null);
            p.setColor(isPressed()?0xFF596E7D:0xFFF3FCFD);c.drawRect(l,t,r,t+2*d,p);c.drawRect(l,t,l+2*d,b,p);p.setColor(0xFF546775);c.drawRect(l,b-2*d,r,b,p);c.drawRect(r-2*d,t,r,b,p);
            p.setColor(0xFF738793);c.drawRect(l+3*d,t+3*d,r-3*d,t+4*d,p);if(isPressed()){p.setColor(0x33000000);c.drawRect(l,t,r,b,p);}
            if(symbol.isEmpty()){super.onDraw(c);return;}
            c.save();c.translate(getWidth()/2f,getHeight()/2f);c.scale(d,d);p.setColor(0xFFEFF9FA);drawIcon(c,1,1);p.setColor(0xFF445968);drawIcon(c,0,0);c.restore();
        }
        void triangle(Canvas c,float x,float y,boolean left){Path path=new Path();path.moveTo(x+(left?5:-5),y-8);path.lineTo(x+(left?-5:5),y);path.lineTo(x+(left?5:-5),y+8);path.close();c.drawPath(path,p);}
        void drawIcon(Canvas c,float x,float y){switch(symbol){
            case "play"->triangle(c,x,y,false);
            case "previous"->{c.drawRect(x-8,y-8,x-5,y+8,p);triangle(c,x+1,y,true);}
            case "next"->{triangle(c,x-1,y,false);c.drawRect(x+5,y-8,x+8,y+8,p);}
            case "pause"->{c.drawRect(x-6,y-8,x-2,y+8,p);c.drawRect(x+2,y-8,x+6,y+8,p);}
            case "stop"->c.drawRect(x-6,y-6,x+6,y+6,p);
            case "sleep"->{Path moon=new Path();moon.moveTo(x+4,y-9);moon.cubicTo(x-12,y-10,x-12,y+10,x+4,y+9);moon.cubicTo(x-4,y+6,x-4,y-6,x+4,y-9);c.drawPath(moon,p);}
            case "edit"->{
                c.save();c.rotate(-40,x,y);c.drawRect(x-2,y-8,x+2,y+5,p);
                Path tip=new Path();tip.moveTo(x-2,y+6);tip.lineTo(x+2,y+6);tip.lineTo(x,y+10);tip.close();c.drawPath(tip,p);c.restore();
            }
            case "back15","forward30"->{
                boolean back=symbol.equals("back15");p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);
                c.drawArc(x-9,y-9,x+9,y+9,back?210:330,240,false,p);p.setStyle(Paint.Style.FILL);
                Path arrow=new Path();float edge=back?x-9:x+9;arrow.moveTo(edge,y-9);arrow.lineTo(edge+(back?5:-5),y-8);arrow.lineTo(edge,y-3);arrow.close();c.drawPath(arrow,p);
                p.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));p.setTextSize(9);p.setTextAlign(Paint.Align.CENTER);c.drawText(back?"15":"30",x,y+3,p);p.setTextAlign(Paint.Align.LEFT);
            }
            case "settings"->{
                Path cog=new Path();cog.setFillType(Path.FillType.EVEN_ODD);
                for(int i=0;i<32;i++){
                    double angle=Math.PI*2*i/32-Math.PI/2;float radius=(i%4==1||i%4==2)?10:7.5f;
                    float px=x+(float)Math.cos(angle)*radius,py=y+(float)Math.sin(angle)*radius;
                    if(i==0)cog.moveTo(px,py);else cog.lineTo(px,py);
                }
                cog.close();cog.addCircle(x,y,3.5f,Path.Direction.CW);c.drawPath(cog,p);
            }
            default->{Path path=new Path();path.moveTo(x,y-7);path.lineTo(x-7,y+3);path.lineTo(x+7,y+3);path.close();c.drawPath(path,p);c.drawRect(x-7,y+5,x+7,y+8,p);}
        }}
    }
    static final class Digits extends View {
        final Paint p=new Paint();String value="00:00";
        final int[] masks={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        Digits(Context c){super(c);setContentDescription("Elapsed time");}
        void setTime(String time){value=time;invalidate();}
        @Override protected void onDraw(Canvas c){float units=0;for(char digit:value.toCharArray())units+=digit==':'?.4f:1.2f;
            float h=getHeight()-8f,w=Math.min((getWidth()-4f)/Math.max(1,units),h*.58f),stroke=Math.max(2,w*.08f),x=2,y=4;
            for(char digit:value.toCharArray()){
                if(digit==':'){p.setColor(GREEN);c.drawRect(x+w*.15f,y+h*.3f,x+w*.15f+stroke,y+h*.3f+stroke,p);c.drawRect(x+w*.15f,y+h*.7f,x+w*.15f+stroke,y+h*.7f+stroke,p);x+=w*.4f;continue;}
                int mask=digit=='-'?0x40:Character.isDigit(digit)?masks[digit-'0']:0;
                float[][] lines={{x,y,x+w,y+stroke},{x+w-stroke,y,x+w,y+h/2},{x+w-stroke,y+h/2,x+w,y+h},{x,y+h-stroke,x+w,y+h},{x,y+h/2,x+stroke,y+h},{x,y,x+stroke,y+h/2},{x,y+h/2-stroke/2,x+w,y+h/2+stroke/2}};
                for(int i=0;i<7;i++){p.setColor((mask&(1<<i))!=0?GREEN:0xFF082109);float[] line=lines[i];c.drawRect(line[0],line[1],line[2],line[3],p);}x+=w*1.2f;
            }
        }
    }
    static final class Spectrum extends View {
        final Paint p=new Paint();final float[] bars=new float[28],peaks=new float[28];
        private boolean playbackActive,frameScheduled;
        private final Runnable frame=()->{frameScheduled=false;if(shouldAnimate()){invalidate();scheduleFrame();}};
        Spectrum(Context c){super(c);setContentDescription("Live music spectrum");}
        void setPlaybackActive(boolean active){
            playbackActive=active;
            if(!active){cancelFrame();boolean hadSignal=false;for(float bar:bars)hadSignal|=bar!=0;for(float peak:peaks)hadSignal|=peak!=0;
                java.util.Arrays.fill(bars,0);java.util.Arrays.fill(peaks,0);if(hadSignal)invalidate();
            }else scheduleFrame();
        }
        private boolean shouldAnimate(){return playbackActive&&AudioDsp.playing&&isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE;}
        private void scheduleFrame(){if(shouldAnimate()&&!frameScheduled){frameScheduled=true;postDelayed(frame,33);}}
        private void cancelFrame(){removeCallbacks(frame);frameScheduled=false;}
        @Override protected void onDraw(Canvas c){boolean active=shouldAnimate();float[] source=AudioDsp.spectrum;float w=getWidth()/28f,h=getHeight()/18f;
            for(int i=0;i<28;i++){
                if(active){bars[i]=Math.max(source[i],bars[i]-.045f);peaks[i]=Math.max(bars[i],peaks[i]-.012f);}
                else{bars[i]=0;peaks[i]=0;}
                int steps=(int)(bars[i]*17);for(int j=0;j<18;j++){p.setColor(j>=steps?0xFF0C180C:j>14?0xFFE6D267:j>10?0xFF99D94E:0xFF45B632);c.drawRect(i*w+1,getHeight()-(j+1)*h,i*w+w-1,getHeight()-j*h-1,p);}
                if(peaks[i]>.02){p.setColor(0xFFC9E9A1);float y=getHeight()-peaks[i]*getHeight();c.drawRect(i*w+1,y,i*w+w-1,y+1,p);}}
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();scheduleFrame();}
        @Override protected void onDetachedFromWindow(){cancelFrame();super.onDetachedFromWindow();}
        @Override protected void onVisibilityChanged(View view,int visibility){super.onVisibilityChanged(view,visibility);if(visibility==VISIBLE)scheduleFrame();else cancelFrame();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)scheduleFrame();else cancelFrame();}
    }
    static final class BandSlider extends View {
        final Paint p=new Paint(3);final String label;final Consumer<Float> changed;float gain;
        BandSlider(Context c,String label,float gain,Consumer<Float> changed){super(c);this.label=label;this.gain=gain;this.changed=changed;setFocusable(true);describe();}
        void describe(){setContentDescription(label+" equalizer gain "+Math.round(gain)+" decibels");}
        @Override protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,x=getWidth()/2f,top=8*d,bottom=getHeight()-21*d,y=top+(12-gain)/24*(bottom-top);
            p.setColor(0xFF080912);c.drawRect(x-3*d,top,x+3*d,bottom,p);p.setColor(0xFFC1AE43);c.drawRect(x-d,top,x+d,bottom,p);p.setColor(0xFFEAE67C);c.drawRect(x-d,top,x,bottom,p);
            p.setColor(0xFFB4C7CF);c.drawRect(x-6*d,y-4*d,x+6*d,y+5*d,p);p.setColor(0xFFEDF8F7);c.drawRect(x-6*d,y-4*d,x+6*d,y-2*d,p);p.setColor(0xFF45596B);c.drawRect(x-3*d,y-d,x+3*d,y+d,p);
            p.setColor(MUTED);p.setTypeface(MONO);p.setTextSize(8*d);p.setTextAlign(Paint.Align.CENTER);c.drawText(label,x,getHeight()-5*d,p);
        }
        @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_DOWN||event.getAction()==MotionEvent.ACTION_MOVE){getParent().requestDisallowInterceptTouchEvent(true);float d=getResources().getDisplayMetrics().density;gain=Math.max(-12,Math.min(12,12-24*(event.getY()-8*d)/(getHeight()-29*d)));gain=Math.round(gain);describe();changed.accept(gain);invalidate();return true;}if(event.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(SeekBar.class.getName());info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,-12,12,gain));info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
        @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){gain=Math.max(-12,Math.min(12,gain+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1)));changed.accept(gain);describe();invalidate();return true;}return super.performAccessibilityAction(action,args);}
    }
}
