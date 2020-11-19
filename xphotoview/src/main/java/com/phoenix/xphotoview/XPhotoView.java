package com.phoenix.xphotoview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.support.v7.widget.AppCompatImageView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 1. 首先它能显示图片初始的样子（无论是全貌还是概貌）
 * 2. 支持手势操作达到图片缩放、漫游的效果，当然，它有一定的缩放倍数限制
 * 3. 支持无损查看高清图片并控制内存使用，理论上讲，能支持 "无限" 的缩放
 * 4. 更高级的，能支持图片编辑、图片批注、图片分享保存等功能
 */
public class XPhotoView extends AppCompatImageView implements IXPhotoView {

    private static final String TAG = "XPhotoView";

    private IXPhotoViewLinker mPhotoViewLinker;

    private XPhotoViewGestureManager mGestureManager;
    private OnTabListener mSingleTabListener;
    private DoubleTabScale mDefaultDoubleTabScale;

    private XPhotoViewCallback.OnXPhotoLoadListener mListener;

    private boolean mScaleEnable = true;

    private Movie mMovie;
    private long mMovieStart;
    private boolean mGif = false;

    public XPhotoView(Context context) {
        this(context, null, 0);
    }

    public XPhotoView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XPhotoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(context, attrs);
        mPhotoViewLinker = new XPhotoViewLinker(this);
        mGestureManager = new XPhotoViewGestureManager(this.getContext(), this, mPhotoViewLinker);
    }

    /**
     * 获取默认配置属性，如 ScaleType 等
     */
    private void initialize(Context context, AttributeSet attrs) {
        mDefaultDoubleTabScale = DoubleTabScale.CENTER_CROP;
        super.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return false;
            }
        });
    }

    public void setScaleEnable(boolean flag) {
        mScaleEnable = flag;
    }

    public void setLoadListener(XPhotoViewCallback.OnXPhotoLoadListener listener) {
        mListener = listener;
    }

    public void setSingleTabListener(OnTabListener listener) {
        mSingleTabListener = listener;
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        onSetImageFinished(null, true, null);
    }

    public void setImageResource(@DrawableRes int resId) {
        Drawable drawable = this.getContext().getResources().getDrawable(resId);
        if (drawable == null) {
            setImageWithStream((FileInputStream) null);
            return;
        }
        this.setImageDrawable(drawable);
    }

    public void setImage(Bitmap image) {
        if (mGif) {
            return;
        }
        super.setImageBitmap(image);
        if (mListener != null) {
            mListener.onImageLoadStart(this);
            Log.d(TAG, "setImage: time: " + System.currentTimeMillis());
        }
        mPhotoViewLinker.setBitmap(image, false);
    }

    public void setImage(String path) {
        setImage(new File(path));
    }

    public void setImage(File file) {

        if (file == null || !file.exists()) {
            setImageWithStream(null);
            return;
        }

        presetImage(file);

        FileInputStream fileInputStream = null;
        try {
            // 检查是否是 GIF
            checkGifPicture(file);
            fileInputStream = new FileInputStream(file);
            setImageWithStream(fileInputStream);
        } catch (FileNotFoundException exp) {
            Log.e(TAG, "setImage failed file " + exp.getMessage());
        } finally {
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 预置图片
     * @param file
     */
    private void presetImage(File file) {
        if (file.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
            super.setImageBitmap(bitmap);
        }
    }

    public void setImageWithStream(InputStream ios) {
        this.setImageAsStream(ios);
    }

    public void setImageWithStream(FileInputStream ios) {
        this.setImageAsStream(ios);
    }

    private void setImageAsStream(InputStream ios) {
        if (mListener != null) {
            mListener.onImageLoadStart(this);
            Log.d(TAG, "setImage: time: " + System.currentTimeMillis());
        }

        if (mGif) {
            try {
                byte[] byteArray = inputStreamToByte(ios);
                mMovie = Movie.decodeByteArray(byteArray, 0, byteArray.length);
            } catch (IOException ignored) { }

            if (mMovie != null) {
                // it's a gif
                mScaleEnable = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }
            }
            onSetImageFinished(null, true, null);
        } else {
            mPhotoViewLinker.setInputStream(ios, Bitmap.Config.RGB_565);
        }
    }

    private byte[] inputStreamToByte(InputStream in) throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int count;
        while ((count = in.read(data, 0, 1024)) != -1) {
            outStream.write(data, 0, count);
        }
        return outStream.toByteArray();
    }

    public void setGif(boolean sGif) {
        this.mGif = sGif;
    }

    public void setGif(byte[] byteArray) {
        mGif = true;
        mMovie = Movie.decodeByteArray(byteArray, 0, byteArray.length);
        if (mMovie != null) {
            mScaleEnable = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
        }
        onSetImageFinished(null, true, null);
    }

    private void checkGifPicture(File file) {
        FileInputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(file);
            mMovie = Movie.decodeStream(fileInputStream);
            mGif = mMovie != null;
        } catch (Exception e) {
            Log.e(TAG, "checkGifPicture occur a exception " + e.getMessage());
        } finally {
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void interceptParentTouchEvent(boolean intercept) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(intercept);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mScaleEnable) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mPhotoViewLinker != null && !mPhotoViewLinker.isNotAvailable()) {
                        interceptParentTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    break;
                case MotionEvent.ACTION_UP:
                    interceptParentTouchEvent(false);
                    break;
            }
        }

        return mGestureManager.onTouchEvent(event);
    }

    @Override
    public void onSingleTab() {
        if (mSingleTabListener != null) {
            mSingleTabListener.onSingleTab();
        }
    }

    @Override
    public void onLongTab() {
        if (mSingleTabListener != null) {
            mSingleTabListener.onLongTab();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mGif && mMovie != null) {
            onGifDraw(canvas);
        } else if (!mGif) {
            mPhotoViewLinker.draw(canvas, getWidth(), getHeight());
        }
    }

    private void onGifDraw(Canvas canvas) {
        canvas.drawColor(Color.TRANSPARENT);
        int vH = getHeight();
        int vW = getWidth();
        int mH = mMovie.height();
        int mW = mMovie.width();
        float scaleX = (float) getWidth() * 1f / mMovie.width();
        float scaleY = (float) getHeight() * 1f / mMovie.height();
        float scale = Math.min(scaleX, scaleY);
        canvas.scale(scale, scale);

        //make sure picture shown in center
        int startY = Math.round((vH * 1f / scale - mH) / 2);
        int startX = Math.round((vW * 1f / scale - mW) / 2);

        long now = android.os.SystemClock.uptimeMillis();

        if (mMovieStart == 0) {
            mMovieStart = (int) now;
        }

        int duration;
        if (mMovie != null) {
            duration = mMovie.duration() == 0 ? 500 : mMovie.duration();
            int relTime = (int) ((now - mMovieStart) % duration);
            mMovie.setTime(relTime);
            mMovie.draw(canvas, startX, startY);
            this.invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        mPhotoViewLinker.onViewSizeChanged(w, h);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mGif && mMovie != null) {
            mMovie = null;
        }
        mPhotoViewLinker.destroy();
    }

    @Override
    public void recycleAll() {
        this.onDetachedFromWindow();
    }

    @Override
    public DoubleTabScale getDoubleTabScale() {
        return mDefaultDoubleTabScale;
    }

    @Override
    public String getCachedDir() {
        return getContext().getFilesDir().getAbsolutePath();
    }

    @Override
    public void callPostInvalidate() {
        postInvalidate();
    }

    @Override
    public void onSetImageFinished(IXPhotoViewLinker bm, boolean success, Rect image) {
        if (mListener != null && success) {
            Log.d(TAG, "onDraw done: time: " + System.currentTimeMillis());
            mListener.onImageLoaded(this);
        }
    }

    interface OnTabListener {
        void onSingleTab();
        void onLongTab();
    }

}