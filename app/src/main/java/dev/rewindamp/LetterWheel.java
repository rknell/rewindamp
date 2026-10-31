package dev.rewindamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** A vertical, knurled thumbwheel with detents, rather than an alphabet scrollbar. */
final class LetterWheel extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> letters=new ArrayList<>();
    private final IntConsumer onSelect;
    private int selected;
    private float lastY,travel,downY;
    private boolean dragging;
    LetterWheel(Context context,IntConsumer onSelect){
        super(context);this.onSelect=onSelect;setFocusable(true);setClickable(true);
        setBackground(Retro.bevel(Retro.PANEL,true));describe();
    }
    static String letter(String value){
        String stripped=value.stripLeading();if(stripped.isEmpty())return "#";
        int first=stripped.codePointAt(0);
        if(first>='a'&&first<='z')return String.valueOf((char)(first-'a'+'A'));
        if(first>='A'&&first<='Z')return String.valueOf((char)first);
        if(!Character.isLetter(first))return "#";
        String normalized=Normalizer.normalize(new String(Character.toChars(first)),Normalizer.Form.NFD).toUpperCase(Locale.ROOT);
        return new String(Character.toChars(normalized.codePointAt(0)));
    }
    void setLetters(List<String> values){letters.clear();letters.addAll(values);selected=Math.min(selected,Math.max(0,letters.size()-1));setEnabled(!letters.isEmpty());describe();invalidate();}
    void select(int index){if(dragging||selected==index)return;selected=index;describe();invalidate();}
    private void describe(){setContentDescription("Library letter wheel"+(letters.isEmpty()?"":", "+letters.get(selected))+". Swipe up or down to jump by letter.");}
    private void step(int amount){
        int target=Math.max(0,Math.min(letters.size()-1,selected+amount));if(target==selected||letters.isEmpty())return;
        selected=target;describe();invalidate();performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);onSelect.accept(selected);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight(),cy=h/2;
        float left=9*d,right=w-9*d,top=Math.max(30*d,cy-112*d),bottom=Math.min(h-20*d,cy+112*d),radius=(bottom-top)/2;
        if(radius<=0)return;
        // Recessed housing and axle caps frame a vertically rotating metal drum.
        paint.setColor(0xFF080A0E);canvas.drawRoundRect(left-4*d,top-7*d,right+4*d,bottom+7*d,6*d,6*d,paint);
        paint.setColor(0xFF778795);canvas.drawRect(left+4*d,top-5*d,right-4*d,top,paint);canvas.drawRect(left+4*d,bottom,right-4*d,bottom+5*d,paint);
        paint.setShader(new LinearGradient(0,top,0,bottom,new int[]{0xFF151C24,0xFF53636E,0xFFBFCBD0,0xFFE0E8E9,0xFFA5B4BE,0xFF394650,0xFF10171E},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(left,top,right,bottom,4*d,4*d,paint);paint.setShader(null);
        canvas.save();canvas.clipRect(left,top,right,bottom);
        // Project the ribs onto a cylinder: they crowd together around its curved ends.
        float phase=dragging?travel/(18*d)*.18f:0;
        for(int i=-24;i<=24;i++){
            double angle=i*Math.PI/26+phase;if(angle< -Math.PI/2||angle>Math.PI/2)continue;
            float y=cy+(float)Math.sin(angle)*radius;
            paint.setStrokeWidth(d);paint.setColor(0xFF3C4A55);canvas.drawLine(left+2*d,y,right-2*d,y,paint);
            paint.setColor(0xFFECF3F4);canvas.drawLine(left+2*d,y+d,right-2*d,y+d,paint);
        }
        // A dark legend strip rotates with the drum; the housing's pointer stays fixed.
        paint.setColor(0xE6172028);canvas.drawRect(w/2-10*d,top,w/2+10*d,bottom,paint);
        paint.setTypeface(Retro.MONO);paint.setTextAlign(Paint.Align.CENTER);
        for(int delta=-3;delta<=3;delta++){
            int index=selected+delta;if(index<0||index>=letters.size()||delta==0)continue;
            float y=cy+delta*27*d;paint.setTextSize((14-Math.abs(delta))*d);paint.setColor(Math.abs(delta)==1?Retro.WHITE:Retro.MUTED);canvas.drawText(letters.get(index),w/2,y+4*d,paint);
        }
        canvas.restore();
        paint.setColor(0xFF030604);canvas.drawRoundRect(left-2*d,cy-18*d,right+2*d,cy+18*d,3*d,3*d,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d);paint.setColor(0xFF6B7E87);canvas.drawRoundRect(left-2*d,cy-18*d,right+2*d,cy+18*d,3*d,3*d,paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(Retro.GREEN);paint.setTextSize(25*d);canvas.drawText(letters.isEmpty()?"–":letters.get(selected),w/2,cy+9*d,paint);
        paint.setColor(Retro.GOLD);Path pointer=new Path();pointer.moveTo(2*d,cy-5*d);pointer.lineTo(8*d,cy);pointer.lineTo(2*d,cy+5*d);pointer.close();canvas.drawPath(pointer,paint);
        paint.setTextSize(8*d);paint.setColor(Retro.GOLD);canvas.drawText("LETTER",w/2,top-15*d,paint);
        // Machined housing screws.
        for(float y:new float[]{top-9*d,bottom+10*d})for(float x:new float[]{5*d,w-5*d}){
            paint.setColor(0xFF7D8B94);canvas.drawCircle(x,y,2*d,paint);paint.setColor(0xFF161C24);canvas.drawLine(x-d,y,x+d,y,paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;float d=getResources().getDisplayMetrics().density;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN->{getParent().requestDisallowInterceptTouchEvent(true);dragging=true;lastY=downY=event.getY();travel=0;setPressed(true);return true;}
            case MotionEvent.ACTION_MOVE->{travel+=lastY-event.getY();lastY=event.getY();int steps=(int)(travel/(18*d));if(steps!=0){travel-=steps*18*d;step(steps);}invalidate();return true;}
            case MotionEvent.ACTION_UP->{if(Math.abs(event.getY()-downY)<android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()){step(event.getY()<getHeight()/2f?-1:1);performClick();}release();return true;}
            case MotionEvent.ACTION_CANCEL->{release();return true;}
            default->{return true;}
        }
    }
    private void release(){dragging=false;travel=0;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_UP){step(key==KeyEvent.KEYCODE_DPAD_DOWN?1:-1);return true;}return super.onKeyDown(key,event);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setScrollable(letters.size()>1);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,Math.max(0,letters.size()-1),selected));
        if(selected<letters.size()-1)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if(selected>0)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args){
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){step(1);return true;}
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){step(-1);return true;}
        return super.performAccessibilityAction(action,args);
    }
}
