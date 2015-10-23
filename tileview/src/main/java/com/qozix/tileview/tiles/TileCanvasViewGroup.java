package com.qozix.tileview.tiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;

import com.qozix.tileview.detail.DetailLevel;
import com.qozix.tileview.graphics.BitmapProvider;
import com.qozix.tileview.graphics.BitmapProviderAssets;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;

public class TileCanvasViewGroup extends ViewGroup implements TileCanvasView.TileCanvasDrawListener {

  private static final int RENDER_FLAG = 1;

  private static final int DEFAULT_RENDER_BUFFER = 250;
  private static final int DEFAULT_TRANSITION_DURATION = 200;

  private LinkedList<Tile> mTilesScheduledToRender = new LinkedList<Tile>();
  private LinkedList<Tile> mTilesAlreadyRendered = new LinkedList<Tile>();

  private BitmapProvider mBitmapProvider;
  private HashMap<Float, TileCanvasView> mTileCanvasViewHashMap = new HashMap<Float, TileCanvasView>();

  private TileRenderTask mLastRunTileRenderTask;

  private DetailLevel mDetailLevelToRender;
  private DetailLevel mLastRenderedDetailLevel;
  private TileCanvasView mCurrentTileCanvasView;

  private boolean mRenderIsCancelled = false;
  private boolean mRenderIsSuppressed = false;
  private boolean mIsRendering = false;

  private boolean mTransitionsEnabled = true;
  private int mTransitionDuration = DEFAULT_TRANSITION_DURATION;

  private TileRenderHandler mTileRenderHandler;
  private TileRenderListener mTileRenderListener;

  private int mRenderBuffer = DEFAULT_RENDER_BUFFER;

  private float mScale = 1;

  private Rect mClipRect = new Rect();

  public TileCanvasViewGroup( Context context ) {
    super( context );
    setWillNotDraw( false );
    setBackgroundColor( 0xffff9900 );
    mTileRenderHandler = new TileRenderHandler( this );
  }

  public float getScale() {
    return mScale;
  }

  public void setScale( float scale ) {
    mScale = scale;
    invalidate();
  }

  public boolean getTransitionsEnabled() {
    return mTransitionsEnabled;
  }

  public void setTransitionsEnabled( boolean enabled ) {
    mTransitionsEnabled = enabled;
  }

  public int getTransitionDuration() {
    return mTransitionDuration;
  }

  public void setTransitionDuration( int duration ) {
    mTransitionDuration = duration;
  }

  public BitmapProvider getBitmapProvider(){
    if( mBitmapProvider == null ) {
      mBitmapProvider = new BitmapProviderAssets();
    }
    return mBitmapProvider;
  }

  public void setBitmapProvider( BitmapProvider bitmapProvider ) {
    mBitmapProvider = bitmapProvider;
  }

  public void setTileRenderListener( TileRenderListener tileRenderListener ) {
    mTileRenderListener = tileRenderListener;
  }

  public int getRenderBuffer() {
    return mRenderBuffer;
  }

  public void setRenderBuffer( int renderBuffer ) {
    mRenderBuffer = renderBuffer;
  }

  @Override
  protected void onMeasure( int widthMeasureSpec, int heightMeasureSpec ) {
    int width = MeasureSpec.getSize( widthMeasureSpec );
    int height = MeasureSpec.getSize( heightMeasureSpec );
    setMeasuredDimension( width, height );
  }

  @Override
  protected void onLayout( boolean changed, int l, int t, int r, int b ) {
    int availableWidth = r - l;
    int availableHeight = b - t;
    for( int i = 0; i < getChildCount(); i++ ) {
      View child = getChildAt( i );
      if( child.getVisibility() != GONE ) {
        child.layout( 0, 0, availableWidth, availableHeight );
      }
    }
  }

  public void requestRender() {
    mRenderIsCancelled = false;
    mRenderIsSuppressed = false;
    if( mDetailLevelToRender == null ) {
      return;
    }
    if( !mTileRenderHandler.hasMessages( RENDER_FLAG ) ) {
      mTileRenderHandler.sendEmptyMessageDelayed( RENDER_FLAG, mRenderBuffer );
    }
  }

  /**
   * Prevent new render tasks from starting, attempts to interrupt ongoing tasks, and will
   * prevent queued tiles from begin decoded or rendered.
   */
  public void cancelRender() {
    mRenderIsCancelled = true;
    if( mLastRunTileRenderTask != null && mLastRunTileRenderTask.getStatus() != AsyncTask.Status.FINISHED ) {
      mLastRunTileRenderTask.cancel( true );
    }
    mLastRunTileRenderTask = null;
  }

  /**
   * Prevent new render tasks from starting, but does not cancel any ongoing operations.
   */
  public void suppressRender() {
    mRenderIsSuppressed = true;
  }

  public void updateTileSet( DetailLevel detailLevel ) {
    mDetailLevelToRender = detailLevel;
    if( mDetailLevelToRender == null ) {
      return;
    }
    if( mDetailLevelToRender.equals( mLastRenderedDetailLevel ) ) {
      return;
    }
    mLastRenderedDetailLevel = mDetailLevelToRender;
    mCurrentTileCanvasView = getCurrentTileCanvasView();
    mCurrentTileCanvasView.bringToFront();
    cancelRender();
    requestRender();
  }

  public boolean getIsRendering() {
    return mIsRendering;
  }

  public void clear() {
    suppressRender();
    cancelRender();
    for( Tile m : mTilesScheduledToRender ) {
      m.destroy();
    }
    mTilesScheduledToRender.clear();
    for( Tile m : mTilesAlreadyRendered ) {
      m.destroy();
    }
    mTilesAlreadyRendered.clear();
  }

  private float getCurrentDetailLevelScale() {
    if( mDetailLevelToRender != null ) {
      return mDetailLevelToRender.getScale();
    }
    return 1;
  }

  private TileCanvasView getCurrentTileCanvasView() {
    float levelScale = getCurrentDetailLevelScale();
    if( mTileCanvasViewHashMap.containsKey( levelScale ) ) {
      return mTileCanvasViewHashMap.get( levelScale );
    }
    TileCanvasView tileGroup = new TileCanvasView( getContext() );
    tileGroup.setTileCanvasDrawListener( this );
    tileGroup.setScale( 1 / levelScale );
    mTileCanvasViewHashMap.put( levelScale, tileGroup );
    addView( tileGroup );
    return tileGroup;
  }

  void renderTiles() {
    if( !mRenderIsCancelled && !mRenderIsSuppressed && mDetailLevelToRender != null ) {
      beginRenderTask();
    }
  }

  private void beginRenderTask() {
    boolean changed = mDetailLevelToRender.computeCurrentState();
    if( !changed ) {
      return;
    }
    mTilesScheduledToRender = mDetailLevelToRender.getVisibleTilesFromLastViewportComputation();
    if( mLastRunTileRenderTask != null && mLastRunTileRenderTask.getStatus() != AsyncTask.Status.FINISHED ) {
      mLastRunTileRenderTask.cancel( true );
    }
    mLastRunTileRenderTask = new TileRenderTask( this );
    mLastRunTileRenderTask.executeOnExecutor( AsyncTask.THREAD_POOL_EXECUTOR );
  }

  private void cleanup() {
    LinkedList<Tile> condemned = new LinkedList<Tile>( mTilesAlreadyRendered );
    condemned.removeAll( mTilesScheduledToRender );
    for( Tile tile : condemned ) {
      tile.destroy();
    }
    mTilesAlreadyRendered.removeAll( condemned );
    mCurrentTileCanvasView.invalidate();
    for( TileCanvasView tileGroup : mTileCanvasViewHashMap.values() ) {
      if( mCurrentTileCanvasView != tileGroup ) {
        tileGroup.clearTiles();
      }
    }
    invalidate();
  }

  void onRenderTaskPreExecute() {
    mIsRendering = true;
    if( mTileRenderListener != null ) {
      mTileRenderListener.onRenderStart();
    }
  }

  void onRenderTaskCancelled() {
    if( mTileRenderListener != null ) {
      mTileRenderListener.onRenderCancelled();
    }
    mIsRendering = false;
  }

  void onRenderTaskPostExecute() {
    mIsRendering = false;
    if( !mTransitionsEnabled ) {
      cleanup();
    }
    if( mTileRenderListener != null ) {
      mTileRenderListener.onRenderComplete();
    }
    requestRender();
  }

  LinkedList<Tile> getRenderList() {
    return (LinkedList<Tile>) mTilesScheduledToRender.clone();
  }

  void generateTileBitmap( Tile tile ) {
    tile.generateBitmap( getContext(), getBitmapProvider() );
  }

  void addTileToCurrentTileCanvasView( Tile tile ) {
    if( mTilesAlreadyRendered.contains( tile ) ) {
      return;
    }
    tile.setTransitionsEnabled( mTransitionsEnabled );
    tile.setTransitionDuration( mTransitionDuration );
    tile.stampTime();
    mTilesAlreadyRendered.add( tile );
    mCurrentTileCanvasView.addTile( tile );
  }

  boolean getRenderIsCancelled() {
    return mRenderIsCancelled;
  }

  @Override
  public void onCleanDrawComplete( TileCanvasView tileCanvasView ) {
    if( mTransitionsEnabled && tileCanvasView == mCurrentTileCanvasView ) {
      cleanup();
    }
  }

  /**
   * The layout dimensions supplied to this ViewGroup will be exactly as large as the scaled
   * width and height of the containing ZoomPanLayout (or TileView).  However, when the canvas
   * is scaled, it's clip area is also scaled - this method will "unscale" the total clip area
   * while preserving the visual transformation.
   * @param canvas The canvas whose bounds are to be redefined.
   */


  /*
  @Override
  public void onDraw( Canvas canvas ) {
    canvas.scale( mScale, mScale );
    super.onDraw( canvas );
  }
  */


  @Override
  public void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.scale( mScale, mScale );
    canvas.restore();
  }


  private static class TileRenderHandler extends Handler {

    private final WeakReference<TileCanvasViewGroup> mTileManagerWeakReference;

    public TileRenderHandler( TileCanvasViewGroup tileCanvasViewGroup ) {
      super();
      mTileManagerWeakReference = new WeakReference<TileCanvasViewGroup>( tileCanvasViewGroup );
    }

    @Override
    public final void handleMessage( Message message ) {
      final TileCanvasViewGroup tileCanvasViewGroup = mTileManagerWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        tileCanvasViewGroup.renderTiles();
      }
    }
  }

  /**
   * Interface definition for callbacks to be invoked after render operations.
   */
  public interface TileRenderListener {
    void onRenderStart();
    void onRenderCancelled();
    void onRenderComplete();
  }

  static class TileRenderTask extends AsyncTask<Void, Tile, Void> {

    private final WeakReference<TileCanvasViewGroup> mTileManagerWeakReference;

    TileRenderTask( TileCanvasViewGroup tileCanvasViewGroup ) {
      super();
      mTileManagerWeakReference = new WeakReference<TileCanvasViewGroup>( tileCanvasViewGroup );
    }

    @Override
    protected void onPreExecute() {
      final TileCanvasViewGroup tileCanvasViewGroup = mTileManagerWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        tileCanvasViewGroup.onRenderTaskPreExecute();
      }
    }

    /**
     * As of 10/03/15, lint is _incorrectly_ indicating that we can't access member
     * variables from a worker thread (this thread).
     * https://code.google.com/p/android/issues/detail?id=175397
     * Until this is corrected, use @SuppressWarnings
     *
     * @param params noop
     * @return null
     */
    @SuppressWarnings("all")
    @Override
    protected Void doInBackground( Void... params ) {
      TileCanvasViewGroup tileCanvasViewGroup = mTileManagerWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        LinkedList<Tile> renderList = tileCanvasViewGroup.getRenderList();
        for( Tile tile : renderList ) {
          if( !isCancelled() ) {
            tileCanvasViewGroup = mTileManagerWeakReference.get();
            if( tileCanvasViewGroup != null && !tileCanvasViewGroup.getRenderIsCancelled() ) {
              tileCanvasViewGroup.generateTileBitmap( tile );
              publishProgress( tile );
            }
          }
        }
      }
      return null;
    }

    @Override
    protected void onProgressUpdate( Tile... params ) {
      if( !isCancelled() ) {
        TileCanvasViewGroup tileCanvasViewGroup = mTileManagerWeakReference.get();
        if( tileCanvasViewGroup != null && !tileCanvasViewGroup.getRenderIsCancelled() ) {
          Tile tile = params[0];
          tileCanvasViewGroup.addTileToCurrentTileCanvasView( tile );
        }
      }
    }

    @Override
    protected void onPostExecute( Void param ) {
      TileCanvasViewGroup tileCanvasViewGroup = mTileManagerWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        tileCanvasViewGroup.onRenderTaskPostExecute();
      }
    }

    @Override
    protected void onCancelled() {
      TileCanvasViewGroup tileCanvasViewGroup = mTileManagerWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        tileCanvasViewGroup.onRenderTaskCancelled();
      }
    }

  }
}
