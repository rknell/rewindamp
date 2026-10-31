package dev.rewindamp;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.IntConsumer;

/** A horizontal metal selector: Music / Podcasts / Queue are independent destinations. */
class DestinationWheel extends View {
    private static final String[] LABELS={"Music","Podcasts","Queue"};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final IntConsumer change;
    private int selected,gestureStart;
    private float downX,downY,lastX,travel;
    private boolean dragging,rotating;
    DestinationWheel(Context context,int selected,IntConsumer change){super(context);this.selected=selected;this.change=change;setFocusable(true);setClickable(true);setBackground(Retro.bevel(Retro.PANEL,true));describe();}
    private int wrap(int value){return Math.floorMod(value,LABELS.length);}
    private void describe(){setContentDescription("Destination wheel, "+LABELS[selected]+" selected. Left: "+LABELS[wrap(selected-1)]+". Right: "+LABELS[wrap(selected+1)]+". Swipe horizontally or tap a side to switch.");}
    void select(int value){selected=wrap(value);describe();invalidate();}
    private void preview(int direction){select(selected+direction);performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
    private void turn(int direction){if(direction==0)return;preview(direction);change.accept(selected);}
    private void dragTo(float x,float y){
        float dx=x-downX,dy=y-downY;
        if(!rotating){if(Math.abs(dx)>ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(dx)>Math.abs(dy)){rotating=true;travel=downX-x;}}
        else travel+=lastX-x;
        lastX=x;float detent=Retro.dp(getContext(),48);
        while(Math.abs(travel)>=detent){int direction=travel>0?1:-1;travel-=direction*detent;preview(direction);}
        invalidate();
    }
    private void release(){dragging=false;rotating=false;travel=0;setPressed(false);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
    @Override protected void onMeasure(int width,int height){super.onMeasure(width,MeasureSpec.makeMeasureSpec(Retro.dp(getContext(),64),MeasureSpec.EXACTLY));}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight(),cx=w/2,cy=h/2,left=12*d,right=w-12*d,top=9*d,bottom=h-9*d;
        paint.setColor(0xFF080A0E);canvas.drawRoundRect(left-5*d,top-4*d,right+5*d,bottom+4*d,5*d,5*d,paint);
        paint.setShader(new LinearGradient(left,0,right,0,new int[]{0xFF10171E,0xFF53636E,0xFFCAD5D8,0xFFE0E8E9,0xFF98ABB5,0xFF394650,0xFF10171E},null,Shader.TileMode.CLAMP));canvas.drawRoundRect(left,top,right,bottom,4*d,4*d,paint);paint.setShader(null);
        canvas.save();canvas.clipRect(left,top,right,bottom);float radius=(right-left)/2;
        for(int i=-40;i<=40;i++){double angle=i*Math.PI/76-travel/(120*d);if(Math.abs(angle)>Math.PI/2)continue;float x=cx+(float)Math.sin(angle)*radius;paint.setColor(0xFF3C4A55);canvas.drawRect(x,top+2*d,x+d,bottom-2*d,paint);paint.setColor(0xFFECF3F4);canvas.drawRect(x+d,top+2*d,x+2*d,bottom-2*d,paint);}
        paint.setColor(0xE6172028);canvas.drawRect(left,cy-12*d,right,cy+12*d,paint);
        paint.setTypeface(Retro.MONO);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(12*d);paint.setColor(Retro.WHITE);
        canvas.drawText(LABELS[wrap(selected-1)].toUpperCase(java.util.Locale.ROOT),w/6,cy+4*d,paint);canvas.drawText(LABELS[wrap(selected+1)].toUpperCase(java.util.Locale.ROOT),5*w/6,cy+4*d,paint);canvas.restore();
        float window=Math.min(108*d,w*.36f);paint.setColor(Retro.LCD);canvas.drawRoundRect(cx-window/2,cy-17*d,cx+window/2,cy+17*d,3*d,3*d,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d);paint.setColor(dragging?Retro.GREEN:0xFF6B7E87);canvas.drawRoundRect(cx-window/2,cy-17*d,cx+window/2,cy+17*d,3*d,3*d,paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(Retro.GREEN);paint.setTextSize(15*d);canvas.drawText(LABELS[selected].toUpperCase(java.util.Locale.ROOT),cx,cy+5*d,paint);
        paint.setColor(Retro.GOLD);Path pointer=new Path();pointer.moveTo(cx-5*d,2*d);pointer.lineTo(cx,8*d);pointer.lineTo(cx+5*d,2*d);pointer.close();canvas.drawPath(pointer,paint);
        for(float x:new float[]{5*d,w-5*d})for(float y:new float[]{5*d,h-5*d}){paint.setColor(0xFF7D8B94);canvas.drawCircle(x,y,2*d,paint);paint.setColor(0xFF161C24);canvas.drawLine(x-d,y,x+d,y,paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN->{downX=lastX=event.getX();downY=event.getY();gestureStart=selected;travel=0;rotating=false;dragging=true;setPressed(true);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;}
            case MotionEvent.ACTION_MOVE->{if(dragging)dragTo(event.getX(),event.getY());return true;}
            case MotionEvent.ACTION_UP->{
                if(!dragging)return false;dragTo(event.getX(),event.getY());
                boolean turned=rotating,tap=!turned&&Math.hypot(event.getX()-downX,event.getY()-downY)<ViewConfiguration.get(getContext()).getScaledTouchSlop();
                int target=selected,start=gestureStart;release();
                // Preview each detent immediately, but keep this View alive until release
                // so opening another Activity cannot interrupt the rest of the gesture.
                if(turned){if(target!=start)change.accept(target);}
                else if(tap){performClick();turn(event.getX()<getWidth()/3f?-1:event.getX()>2*getWidth()/3f?1:0);}
                return true;
            }
            case MotionEvent.ACTION_CANCEL->{if(dragging)select(gestureStart);release();return true;}
            default->{return true;}
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){turn(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);return true;}return super.onKeyDown(key,event);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setScrollable(true);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){turn(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return true;}return super.performAccessibilityAction(action,args);}
}
