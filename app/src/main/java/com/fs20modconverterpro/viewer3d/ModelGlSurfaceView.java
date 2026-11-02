package com.fs20modconverterpro.viewer3d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/** Superfície OpenGL ES 2.0 para renderização geométrica, com gestos de câmera. */
public final class ModelGlSurfaceView extends GLSurfaceView {
    private final ModelGlRenderer renderer;
    private float previousX, previousY, previousDistance;
    public ModelGlSurfaceView(Context context) {
        super(context); setEGLContextClientVersion(2); renderer=new ModelGlRenderer(); setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }
    public void setMesh(MeshData mesh) { renderer.setMesh(mesh); }
    public void resetCamera() { renderer.resetCamera(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int count=event.getPointerCount();
        if (event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN && count>=2) { previousDistance=distance(event); previousX=(event.getX(0)+event.getX(1))/2; previousY=(event.getY(0)+event.getY(1))/2; }
        else if (event.getActionMasked()==MotionEvent.ACTION_MOVE) {
            if (count==1) { float x=event.getX(),y=event.getY(); renderer.rotate((x-previousX)*0.5f,(y-previousY)*0.5f); previousX=x; previousY=y; }
            else if(count>=2){ float d=distance(event); if(previousDistance>0) renderer.zoom(previousDistance/d); float cx=(event.getX(0)+event.getX(1))/2,cy=(event.getY(0)+event.getY(1))/2; renderer.pan((cx-previousX)*0.005f,(cy-previousY)*0.005f); previousX=cx;previousY=cy;previousDistance=d; }
        } else if(event.getActionMasked()==MotionEvent.ACTION_DOWN){previousX=event.getX();previousY=event.getY();}
        else if(event.getActionMasked()==MotionEvent.ACTION_POINTER_UP)previousDistance=0;
        return true;
    }
    private static float distance(MotionEvent e){float x=e.getX(0)-e.getX(1),y=e.getY(0)-e.getY(1);return (float)Math.sqrt(x*x+y*y);}
}
