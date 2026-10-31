package dev.rewindamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ListView;

/** Pull gestures belong to the list, never to the adjacent letter wheel. */
final class PullRefreshLayout extends FrameLayout {
    private static final int REFRESH=0x01000001;
    private final ListView list;
    private final Runnable refresh;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float downX,downY,distance;
    private boolean eligible,pulling;
    PullRefreshLayout(Context context,View contents,ListView list,Runnable refresh){super(context);this.list=list;this.refresh=refresh;setWillNotDraw(false);addView(contents,new LayoutParams(-1,-1));}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();distance=0;pulling=false;eligible=isEnabled()&&event.getX()<list.getRight()&&!list.canScrollVertically(-1);}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&eligible){float dy=event.getY()-downY;pulling=dy>ViewConfiguration.get(getContext()).getScaledTouchSlop()&&dy>Math.abs(event.getX()-downX)&&!list.canScrollVertically(-1);if(pulling){getParent().requestDisallowInterceptTouchEvent(true);return true;}}
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!pulling)return false;
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){distance=Math.max(0,event.getY()-downY);getChildAt(0).setTranslationY(Math.min(Retro.dp(getContext(),44),distance/3));invalidate();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){distance=Math.max(0,event.getY()-downY);boolean trigger=event.getActionMasked()==MotionEvent.ACTION_UP&&distance>=Retro.dp(getContext(),72);pulling=false;distance=0;getChildAt(0).setTranslationY(0);getParent().requestDisallowInterceptTouchEvent(false);invalidate();if(trigger)refresh.run();return true;}
        return true;
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(!pulling)return;paint.setColor(Retro.GOLD);paint.setTypeface(Retro.MONO);paint.setTextSize(Retro.dp(getContext(),11));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(distance>=Retro.dp(getContext(),72)?"RELEASE TO REFRESH":"PULL TO REFRESH",getWidth()/2f,Retro.dp(getContext(),22),paint);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);if(isEnabled())info.addAction(new AccessibilityNodeInfo.AccessibilityAction(REFRESH,"Refresh library"));}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==REFRESH&&isEnabled()){refresh.run();return true;}return super.performAccessibilityAction(action,args);}
}
