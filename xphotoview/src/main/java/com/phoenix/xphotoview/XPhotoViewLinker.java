package com.phoenix.xphotoview;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 支持输入类型 :
 * 1.原图 Bitmap
 * 2.IO Stream
 * 3.File
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * 外部设置图像流程：
 * - 如果设置源是「原图」则先缓存为文件，再通过 IOStream 进入 2 3 流程
 * - 初始化方法 initialize 是必经之地，预示图片处理正式开始
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * 基本逻辑：
 * - 默认将图片以 CENTER_INSIDE 进行压缩显示，计算出默认 SampleSize 进行切割
 * - 保存切割后 bitmap 作为 ThumbSampleBitmap，保存 SampleSize 为 DefaultSampleSize
 * - 视图大小 mViewRect，可以理解为以 View 为坐标系，保持不变
 * - 原图大小 mImageRect，大小保持不变
 * - 压缩后图片实际大小 mShowBitmapRect, 视图在图片上的位置 mViewBitmapRect，均以 Bitmap 坐标系为准
 * - 缩放时更新 mShowBitmapRect 大小，重新计算 SampleSize 并更新视图
 * - 移动时更新 mViewBitmapRect 并更新视图，此时不需要更新 SampleSize
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * 解析逻辑：
 * - 在线程中用 BitmapRegionDecoder 将原图进行 N * M 个方格进行切割再分别解析
 * - 线程 mInstanceDecoderRunnable 为解析过程
 */

class XPhotoViewLinker implements IXPhotoViewLinker {

    private static final String TAG = "XPhotoViewLinker";

    private static final float MAX_SCALE_VALUE = 4;
    private static final float MIN_SCALE_VALUE = 1;
    private static final float DOUBLE_TAP_MAX = 2.0f;

    private XPhotoView mPhotoView;

    /**
     * 缩放动画
     */
    private ValueAnimator mValueAnimator = null;
    private float mLastAnimatedValue = 1f;

    private float mMaxScaleValue;
    private float mMinScaleValue;

    private boolean sIsSettingImage;

    /**
     * 保存外部业务直接设置进来的 bitmap
     */
    private Bitmap mSourceBitmap = null;

    /**
     * 将外部业务直接设置进来的 Bitmap 缓存成物理文件
     */
    private final File mSourceBitmapCacheFile;

    /**
     * 质量参数, 默认为 RGB_565
     */
    private Bitmap.Config mBitmapConfig = Bitmap.Config.RGB_565;

    /**
     * 当前图片的的采样率
     */
    private int mSampleSize = 0;

    /**
     * 缩略图时的 SampleSize
     */
    private int mThumbSampleSize = 0;

    /***
     * View Rect
     * View 坐标系*/
    private Rect mOriginViewRect = new Rect();

    /**
     * 原图 Rect，由 BitmapRegionDecoder 读取获得
     * Bitmap 坐标系
     */
    private Rect mOriginImageRect = new Rect();

    /**
     * 当下展示的 Bitmap 大小（受缩放影响，实际展示的 Bitmap 全幅的大小区域）
     * Bitmap 坐标系
     */
    private RectF mShowBitmapRect = new RectF();

    /**
     * View 相对 Show Bitmap 的坐标
     * Bitmap 坐标系（View 是以 Show Bitmap 为参考系来确定自己的位置，所以是 Bitmap 坐标系）
     */
    private Rect mShowBitmapViewRect = new Rect();

    /**
     * 第一次初始化后的默认 Show Bitmap Rect
     * Bitmap 坐标系
     */
    private Rect mInitiatedShowBitmapRect = new Rect();

    /**
     * 局部解析原始图片工具
     */
    private BitmapRegionDecoder mBitmapRegionDecoder;

    /**
     * Decoder 解析用的 IS，即外部传入的文件流，与 mSourceBitmap 有同样的含义
     */
    private InputStream mSourceInputStream;

    /**
     * Bitmap 网格策略
     */
    private BitmapGridStrategy mBitmapGridStrategy = new BitmapGridStrategy();


    /**
     * 异步处理图片的解码
     */
    private Handler mLoadingHandler = null;
    private final Handler mMainHandler = new Handler();
    private HandlerThread mLoadingThread;
    private static final String THREAD_TAG = "LoadingThread";
    private final Object mDecodeSyncLock = new Object();

    /**
     * 将输入的 Bitmap 原图缓存为文件再输出为 fos 的线程
     */
    private Runnable mCacheBitmapRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                FileOutputStream fos = new FileOutputStream(mSourceBitmapCacheFile);
                mSourceBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.flush();
                fos.close();

                setBitmapDecoder(new FileInputStream(mSourceBitmapCacheFile));
            } catch (Exception e) {
                e.printStackTrace();
                onSetImageFinished(false);
            }
        }
    };

    /**
     * 读取输入流初始化 BitmapRegionDecoder
     * - 丈量原图的宽高信息
     * - 丈量
     */
    private Runnable mInstanceDecoderRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                mBitmapRegionDecoder = BitmapRegionDecoder.newInstance(mSourceInputStream, false);
                mOriginImageRect.set(0, 0, mBitmapRegionDecoder.getWidth(), mBitmapRegionDecoder.getHeight());
            } catch (IOException e) {
                e.printStackTrace();
                mBitmapRegionDecoder.recycle();
                mBitmapRegionDecoder = null;
            }

            if (mBitmapRegionDecoder != null) {
                initiateViewRect(mOriginViewRect.width(), mOriginViewRect.height());
            } else {
                onSetImageFinished(false);
            }
        }
    };

    XPhotoViewLinker(XPhotoView mPhotoView) {
        this.mPhotoView = mPhotoView;
        mSourceBitmapCacheFile = new File(mPhotoView.getCachedDir(), UUID.randomUUID().toString());
        mLoadingThread = new HandlerThread(THREAD_TAG + this.hashCode());

    }

    /**
     * 初始化所需参数和线程，预示 Image 设置开始 ...
     */
    private synchronized void initialize(Bitmap.Config config) {

        onSetImageStart();

        if (mLoadingHandler != null) {
            mLoadingHandler.removeCallbacks(mInstanceDecoderRunnable);
            mLoadingHandler.removeCallbacks(mCacheBitmapRunnable);
            mLoadingHandler.removeCallbacks(mBitmapGridStrategy.mDecodeThumbRunnable);
        }

        if (mBitmapRegionDecoder != null) {
            mBitmapRegionDecoder.recycle();
            mBitmapRegionDecoder = null;
        }

        if (mBitmapGridStrategy != null) {
            recycleAll();
        }

        mBitmapConfig = config == null ? Bitmap.Config.RGB_565 : config;

        if (mLoadingThread == null || mLoadingThread.getState() == Thread.State.NEW) {
            mLoadingThread = new HandlerThread(THREAD_TAG + this.hashCode());
            mLoadingThread.start();
        }
        mLoadingHandler = new Handler(mLoadingThread.getLooper());
    }

    @Override
    public void setBitmap(Bitmap bitmap, boolean cache) {
        initialize(Bitmap.Config.ARGB_8888);
        setSrcBitmap(bitmap, cache);
    }

    /**
     * 直接设置 Bitmap
     */
    private void setSrcBitmap(final Bitmap bitmap, boolean enableCache) {
        mSourceBitmap = bitmap;
        if (bitmap == null) {
            onSetImageFinished(true);
            return;
        }

        if (enableCache) {
            mLoadingHandler.post(mCacheBitmapRunnable);
        } else {
            mOriginImageRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());

            initiateViewRect(mOriginViewRect.width(), mOriginViewRect.height());
        }
    }

    @Override
    public void setInputStream(InputStream is, Bitmap.Config config) {
        initialize(config);
        setBitmapDecoder(is);
    }

    /**
     * 设置 BitmapRegionDecoder 这个函数只会走一次
     */
    private void setBitmapDecoder(final InputStream is) {
        mSourceInputStream = is;

        if (is == null) {
            onSetImageFinished(false);
            return;
        }

        mLoadingHandler.post(mInstanceDecoderRunnable);
    }

    private void initiateViewRect(int viewWidth, int viewHeight) {

        mOriginViewRect.set(0, 0, viewWidth, viewHeight);

        int originImgWidth = mOriginImageRect.width();
        int originImgHeight = mOriginImageRect.height();

        // 有任何一个为 0 则异常
        if (viewWidth * viewHeight * originImgWidth * originImgHeight == 0) {
            return;
        }

        //  maxScale = max(MAX_SCALE_VALUE, view 和 image 的边的比值的最大值)
        //  minScale = min(MIN_SCALE_VALUE, view 和 image 的边的比值的最小值)
        //  1. image 一边大于 view 对应边：最大为 View / Image 短边比 或者 4 倍，最小适配屏幕或者 1 倍
        //  2. image 两边大于 view 对应边：最大为 4 倍，最小适配屏幕
        //  3. getMaxFitViewValue 得到的值表明以 CenterCrop 效果呈现 image 需要缩放的比例
        //  4. getMinFitViewValue 得到的值表明以 CenterInside 效果呈现 image 需要缩放的比例
        // 最终得出原图最大最小的缩放比例
        mMaxScaleValue = Math.max(MAX_SCALE_VALUE, getMaxFitViewValue());
        mMinScaleValue = Math.min(MIN_SCALE_VALUE, getMinFitViewValue());
        Log.d("zwt", "init getMinFitViewValue - " + getMinFitViewValue());

        // 比较 view 宽高比 和 image 宽高比（宽高比越小越细长）
        // iW / iH < vW / vH : 左右留空，取高比值，大于等于 1 或小于 1
        // iW / iH > vW / vH : 上下留空，取宽比值，大于等于 1 或小于 1
        // float ratio = (imgWidth / imgHeight < viewWidth / viewHeight) ? (imgHeight * 1.0f / viewHeight) : (imgWidth * 1.0f / viewWidth);
        // imageThinLong - 原图更加细长？
        boolean imageThinLong = originImgWidth * 1.0f / originImgHeight < viewWidth * 1.0f / viewHeight;
        float imageFitViewRatio = imageThinLong ? (originImgHeight * 1.0f / viewHeight) : (originImgWidth * 1.0f / viewWidth);
        Log.d("zwt", "init imageFitViewRatio - " + imageFitViewRatio);

        // Image 在 View 中缩放后应显示的区域大小，注意，只是大小，没有准确的位置
        mShowBitmapRect.set(0, 0, (originImgWidth * 1.0f / imageFitViewRatio), (originImgHeight * 1.0f / imageFitViewRatio));
        Log.d("zwt", "init mShowBitmapRect - " + mShowBitmapRect.toShortString());

        // 保存此初始大小，不可变
        mShowBitmapRect.round(mInitiatedShowBitmapRect);

        // 取缩小到适配 view 时的 bitmap 的起始位置
        int left = (int) ((mShowBitmapRect.width() - mOriginViewRect.width()) / 2);
        left = mShowBitmapRect.width() < mOriginViewRect.width() ? left : 0;

        int top = (int) ((mShowBitmapRect.height() - mOriginViewRect.height()) / 2);
        top = mShowBitmapRect.height() < mOriginViewRect.height() ? top : 0;

        int right = left + mOriginViewRect.width();
        int bottom = top + mOriginViewRect.height();

        mShowBitmapViewRect.set(left, top, right, bottom);

        // 原图 Bitmap 与缩放后在 View 中以 CenterInside 显示的 ShowBitmapRect 进行缩放值的计算
        // 取 imageWidth / ShowBitmapReact.width 还是 imageHeight / ShowBitmapRect.height 值实际上是一样的，
        // 因为此时的 showBitmapRect 和 originImageRect 实际上是等比矩形
        mSampleSize = imageThinLong ? calculateSampleSize((int) (originImgHeight / mShowBitmapRect.height()))
                : calculateSampleSize((int) (originImgWidth / mShowBitmapRect.width()));

        // 保存原图在 View 完整显示（CenterInside）下的缩略图缩放值
        mThumbSampleSize = mSampleSize;

        // 初始化网格矩阵
        mBitmapGridStrategy.initializeBitmapGrid();
    }

    private synchronized boolean checkOrUpdateViewRect(int width, int height) {
        // 做 diff 检查，防止大量计算 in draw
        if (mOriginViewRect.width() != width || mOriginViewRect.height() != height) {
            onSetImageStart();
            initiateViewRect(width, height);
            return true;
        }

        return false;
    }

    /**
     * 重置为初始化状态
     */
    private void resetShowBitmapRect() {
        mShowBitmapRect.set(mInitiatedShowBitmapRect);

        int left = (int) ((mShowBitmapRect.width() - mOriginViewRect.width()) / 2);
        int right = left + mOriginViewRect.width();
        int top = (int) ((mShowBitmapRect.height() - mOriginViewRect.height()) / 2);
        int bottom = top + mOriginViewRect.height();
        mShowBitmapViewRect.set(left, top, right, bottom);
    }

    /**
     * 更新 ShowBitmapViewRect
     *
     * @param rect ShowBitmapRect 相对 view 坐标系的 rect
     */
    private void updateViewBitmapRect(RectF rect) {
        Rect vRect = new Rect(0, 0, mOriginViewRect.width(), mOriginViewRect.height());
        vRect.left = (int) -rect.left;
        vRect.right = vRect.left + mOriginViewRect.width();
        vRect.top = (int) -rect.top;
        vRect.bottom = vRect.top + mOriginViewRect.height();
        mShowBitmapViewRect.set(vRect);
        mShowBitmapRect.set(0, 0, rect.width(), rect.height());
    }

    /***
     * 填满 View 的放大值
     * 取宽比和高比的最大值，相当于 scaleType = CENTER_CROP，即效果为缩放图片以相对 View 居中显示
     * 取 View 和 Image 双边比的最大值进行 Image 的缩放，才能满足 Image 填满 View 的需求
     * 即以与 View 差别最小的边为基准进行放大
     */
    private float getMaxFitViewValue() {
        float imageWidth = mOriginImageRect.width();
        float imageHeight = mOriginImageRect.height();

        float viewWidth = mOriginViewRect.width();
        float viewHeight = mOriginViewRect.height();

        return Math.max(viewWidth / imageWidth, viewHeight / imageHeight);
    }

    /**
     * 相当于 scaleType = CENTER_INSIDE
     * 即将图片的内容完整居中显示，通过按比例缩小使得图片 Width / Height 等于或小于 View 的 Width / Height
     * 即以 View 差别最大的边为基准进行缩小
     */
    private float getMinFitViewValue() {
        float iw = mOriginImageRect.width();
        float ih = mOriginImageRect.height();

        float vw = mOriginViewRect.width();
        float vh = mOriginViewRect.height();
        Log.d("zwt", "init getMinFitViewValue vw * 1f / iw - " + (vw * 1f / iw));
        Log.d("zwt", "init getMinFitViewValue vh * 1f / ih - " + (vh * 1f / ih));
        Log.d("zwt", "init getMinFitViewValue mOriginImageRect.width() - " + (mOriginImageRect.width()));
        Log.d("zwt", "init getMinFitViewValue mOriginImageRect.height() - " + (mOriginImageRect.height()));

        return Math.min(vw * 1f / iw, vh * 1f / ih);
    }

    private float getMaxDoubleTapScaleFactor() {
        return getMaxFitViewScaleFactor() == 1.0f ? DOUBLE_TAP_MAX : getMaxFitViewScaleFactor();
    }

    /**
     * 获取最大适应view的缩放倍数
     */
    private float getMaxFitViewScaleFactor() {
        float ws = mShowBitmapRect.width() == 0 ? 0 : mOriginViewRect.width() * 1f / mShowBitmapRect.width();
        float hs = mShowBitmapRect.height() == 0 ? 0 : mOriginViewRect.height() * 1f / mShowBitmapRect.height();
        Log.d("zwy", "getMaxFitViewScaleFactor ws - " + ws);
        Log.d("zwy", "getMaxFitViewScaleFactor hs - " + hs);
        return Math.max(ws, hs);
    }

    /**
     * 获取最小适应 view 的缩放倍数
     */
    private float getMinFitViewScaleFactor() {
        float ws = mShowBitmapRect.width() == 0 ? 0 : mOriginViewRect.width() * 1f / mShowBitmapRect.width();
        float hs = mShowBitmapRect.height() == 0 ? 0 : mOriginViewRect.height() * 1f / mShowBitmapRect.height();
        Log.d("zwy", "getMinFitViewScaleFactor ws - " + ws);
        Log.d("zwy", "getMinFitViewScaleFactor hs - " + hs);

        return Math.min(ws, hs);
    }

    /**
     * 获取采样率
     */
    private int calculateSampleSize(int size) {
        int sampleSize = 1;
        while (size >> 1 != 0) {
            sampleSize = sampleSize << 1;
            size = size >> 1;
        }
        return sampleSize;
    }

    /**
     * 获取当前的 SampleSize 值
     */
    private int getCurSampleSize() {
        int iw = mOriginImageRect.width();
        int ih = mOriginImageRect.height();
        int bw = (int) mShowBitmapRect.width();
        int bh = (int) mShowBitmapRect.height();
        if (bw * bh == 0) {
            return 1;
        }

        // 以 bitmap 的宽高为标准
        // 分别以宽高为标准，计算对应的的宽高
        // 如果是宽图, 则以 View 的宽为标准
        // 否则为高图，则以 View 的高为标准
        // 求出 SampleSize
        int width = (int) (iw * 1.0f / ih * bh);
        int sampleSize = (width > bw) ? calculateSampleSize(iw / bw) : calculateSampleSize(ih / bh);
        if (sampleSize < 1) {
            sampleSize = 1;
        }

        return sampleSize;
    }

    /**
     * 设置图片开始
     */
    private synchronized void onSetImageStart() {
        sIsSettingImage = true;
    }

    /**
     * 设置图片结束
     */
    private synchronized void onSetImageFinished(final boolean success) {
        sIsSettingImage = false;

        final Rect image = new Rect();
        if (success) {
            image.set(mOriginImageRect);
        }

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mPhotoView.onSetImageFinished(XPhotoViewLinker.this, success, image);
                mPhotoView.callPostInvalidate();
            }
        });
    }

    /**
     * 回收所有内存
     */
    private void recycleAll() {
        mBitmapGridStrategy.recycleAllGrids();

        synchronized (mDecodeSyncLock) {
            if (mBitmapRegionDecoder != null) {
                mBitmapRegionDecoder.recycle();
                mBitmapRegionDecoder = null;
            }

            mSourceBitmap = null;
        }
    }

    @Override
    public boolean draw(@NonNull Canvas canvas, int width, int height) {
        if (isNotAvailable()) {
            return false;
        }

        if (mSourceBitmap != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            int mw = canvas.getMaximumBitmapWidth();
            int mh = canvas.getMaximumBitmapHeight();

            // 如果图片太大，直接使用 bitmap 会占用很大内存，所以建议缓存为文件再显示
            if (mSourceBitmap.getHeight() > mh || mSourceBitmap.getWidth() > mw) {
                // TODO
            }
        }
        // 更新视图或者画出图片
        return !checkOrUpdateViewRect(width, height) && mBitmapGridStrategy.drawVisibleGrid(canvas);
    }

    @Override
    public int move(int dx, int dy) {
        if (isNotAvailable()) {
            return NONE;
        }

        Rect oRect = new Rect();
        toViewCoordinate(mShowBitmapRect).round(oRect);

        // 检测边界
        int rx = dx;
        int ry = dy;

        if (oRect.left >= 0 && oRect.right <= mOriginViewRect.right) {
            rx = Integer.MAX_VALUE;
        }

        if (oRect.top >= 0 && oRect.bottom <= mOriginViewRect.bottom) {
            ry = Integer.MAX_VALUE;
        }

        if (rx != Integer.MAX_VALUE) {
            if (oRect.left + dx > 0) {
                rx = -oRect.left;
            }

            if (oRect.right + dx < mOriginViewRect.right) {
                rx = mOriginViewRect.right - oRect.right;
            }

            if (oRect.left + dx > 0 && oRect.right + dx < mOriginViewRect.right) {
                rx = mOriginViewRect.centerX() - oRect.centerX();
            }
        }

        if (ry != Integer.MAX_VALUE) {
            if (oRect.top + dy > 0) {
                ry = -oRect.top;
            }

            if (oRect.bottom + dy < mOriginViewRect.bottom) {
                ry = mOriginViewRect.bottom - oRect.bottom;
            }

            if (oRect.top + dy > 0 && oRect.bottom + dy < mOriginViewRect.bottom) {
                ry = mOriginViewRect.centerY() - oRect.centerY();
            }
        }

        mShowBitmapViewRect.offset(-(rx == Integer.MAX_VALUE ? 0 : rx), -(ry == Integer.MAX_VALUE ? 0 : ry));
        mPhotoView.callPostInvalidate();

        /**
         * 检查到达边界的方向
         */
        Rect detectRect = new Rect(mShowBitmapViewRect);
        int result = NONE;
        if (detectRect.left <= 0) {
            result |= LEFT;
        }
        if (detectRect.right >= (int) mShowBitmapRect.right) {
            result |= RIGHT;
        }

        if (detectRect.top <= 0) {
            result |= TOP;
        }
        if (detectRect.bottom >= (int) mShowBitmapRect.bottom) {
            result |= BOTTOM;
        }

        return result;
    }

    @Override
    public void onViewSizeChanged(int width, int height) {
        checkOrUpdateViewRect(width, height);
    }

    @Override
    public void scale(float cx, float cy, float scale) {
        if (isNotAvailable()) {
            return;
        }
        Log.d("zwt", "before scale  - " + scale);
        Log.d("zwt", "before scale init rect  - " + mInitiatedShowBitmapRect.toShortString());
        Log.d("zwt", "before scale target rect  - " + mShowBitmapRect.toShortString());

        RectF viewRect = new RectF(mOriginViewRect);

        // 如果图片的长或宽，全在视图内，则以中线进行缩放
        RectF bitmapToViewRect = toViewCoordinate(mShowBitmapRect);
        Log.d("zwt", "before scale bitmapToViewRect rect  - " + bitmapToViewRect.toShortString());

        // 如果宽全在视图内
        if (bitmapToViewRect.left > 0 && bitmapToViewRect.right < mOriginViewRect.right) {
            cx = viewRect.centerX();
        }

        // 如果高全在视图内
        if (bitmapToViewRect.top > 0 && bitmapToViewRect.bottom < mOriginViewRect.bottom) {
            cy = viewRect.centerY();
        }

        // 以 cx, cy 缩放
        float left = (cx - Math.abs(cx - bitmapToViewRect.left) * scale);
        float top = (cy - Math.abs(cy - bitmapToViewRect.top) * scale);
        float right = left + bitmapToViewRect.width() * scale;
        float bottom = top + bitmapToViewRect.height() * scale;

        // 得到缩放后的 Bitmap 相对于 View 的坐标
        RectF nRect = new RectF(left, top, right, bottom);
        Log.d("zwt", "before scale nRect rect  - " + nRect.toShortString());

        // 缩放后的 Bitmap 不得小于初始的 Bitmap Rect
        if (nRect.width() <= mInitiatedShowBitmapRect.width() - 1 || nRect.height() <= mInitiatedShowBitmapRect.height() - 1) {
            Log.d("zwt", "nRect.width() - " + nRect.width());
            Log.d("zwt", "nRect.height() - " + nRect.height());
            resetShowBitmapRect();
            return;
        }

        // 不能再放大或者缩小了
        float scaleValue = nRect.width() / mOriginImageRect.width();
//        float scaleValue = nRect.height() / mOriginImageRect.height();
        float maxDiffScale = scaleValue - mMaxScaleValue;
        float minDiffScale = scaleValue - mMinScaleValue;
        if ((maxDiffScale > 0 && Math.abs(maxDiffScale) > 0.00001) || (minDiffScale < 0 && Math.abs(minDiffScale) > 0.00001)) {
            Log.d("zwt", "nRect.scaleValue - " + scaleValue);
            Log.d("zwt", "nRect.scaleValue nRect.width() - " + nRect.width());
            Log.d("zwt", "nRect.scaleValue mOriginImageRect.width() - " + mOriginImageRect.width());
            Log.d("zwt", "nRect.mMaxScaleValue - " + mMaxScaleValue);
            Log.d("zwt", "nRect.mMinScaleValue - " + mMinScaleValue);
            return;
        }

        // 更新 ShowBitmapViewRect 坐标, 并更新显示的 bitmap rect 大小
        updateViewBitmapRect(nRect);

        /**
         * 如果还是小于视图宽度，则需要移动到正中间
         */
        float nx = 0;
        float ny = 0;
        RectF aRect = toViewCoordinate(mShowBitmapRect);
        if (aRect.width() < viewRect.width()) {
            nx = viewRect.centerX() - aRect.centerX();
        } else {
            if (aRect.left > 0) {
                nx = -aRect.left;
            } else if (aRect.right < viewRect.width()) {
                nx = viewRect.width() - aRect.right;
            }
        }

        if (aRect.height() < viewRect.height()) {
            ny = viewRect.centerY() - aRect.centerY();
        } else {
            if (aRect.top > 0) {
                ny = -aRect.top;
            } else if (aRect.bottom < viewRect.height()) {
                ny = viewRect.height() - aRect.bottom;
            }
        }

        aRect.offset(nx, ny);
        updateViewBitmapRect(aRect);
        Log.d("zwt", "after scale target rect  - " + mShowBitmapRect.toShortString());

        mPhotoView.callPostInvalidate();
    }

    @Override
    public void scaleTo(final int cx, final int cy, float dest, boolean smooth, long smoothTime) {
        if (isNotAvailable()) {
            return;
        }

        if (mValueAnimator != null && mValueAnimator.isRunning()) {
            mValueAnimator.end();
            mValueAnimator.cancel();
        }

        if (smooth) {
            mLastAnimatedValue = 1f;
            ObjectAnimator.ofFloat(1f, dest);
            mValueAnimator = ValueAnimator.ofFloat(1f, dest);
            mValueAnimator.setDuration(smoothTime);
            mValueAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

            mValueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float value = (float) animation.getAnimatedValue();
                    scale(cx, cy, value / mLastAnimatedValue);
                    mLastAnimatedValue = value;
                }
            });

            mValueAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    //
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    updateSampleSize();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    updateSampleSize();
                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            });
            mValueAnimator.start();
        } else {
            scale(cx, cy, dest);
            updateSampleSize();
        }
    }

    @Override
    public void scaleTo(float dest, boolean smooth, long smoothTime) {
        Log.d("zwt", "scaleTo....");
        scaleTo(mOriginViewRect.centerX(), mOriginViewRect.centerY(), dest, smooth, smoothTime);
    }

    @Override
    public void doubleTapScale(int cx, int cy, boolean smooth, long smoothTime) {
        if (mValueAnimator != null && mValueAnimator.isRunning()) {
            return;
        }

        if (isNotAvailable() && mShowBitmapRect.height() > 0 && mShowBitmapRect.width() > 0) {
            return;
        }

        float destScale = 0;

        float sw = mShowBitmapRect.width();
        float sh = mShowBitmapRect.height();

        int tw = mInitiatedShowBitmapRect.width();
        int th = mInitiatedShowBitmapRect.height();

        float maxFitScale = getMaxDoubleTapScaleFactor();
        float minFitScale = getMinFitViewScaleFactor();

        IXPhotoView.DoubleTabScale scale = mPhotoView.getDoubleTabScale();
        if (scale == null) {
            scale = IXPhotoView.DoubleTabScale.CENTER_CROP;
        }

        switch (scale) {
            case CENTER_INSIDE:
                if (sw < mOriginViewRect.width() + 5f && sh < mOriginViewRect.height() + 5f) {
                    destScale = maxFitScale;
                } else {
                    destScale = minFitScale;
                }
                break;

            case CENTER_CROP:
                if ((Math.abs(sw - tw) < 5 && Math.abs(sh - th) < 5)) {
                    destScale = maxFitScale;
                } else {
                    float ws = mOriginImageRect.width() * 1f / mShowBitmapRect.width();
                    float hs = mOriginImageRect.height() * 1f / mShowBitmapRect.height();
                    Log.d("zwt", "1111 ws - " + (ws));
                    Log.d("zwt", "2222 hs - " + (hs));
                    destScale = Math.min(minFitScale, Math.min(ws, hs));
                }
                break;
        }
//        Log.d("zwt", "double Tab Math.abs(sw - tw) - " + (Math.abs(sw - tw)));
//        Log.d("zwt", "double Tab Math.abs(sh - th) - " + (Math.abs(sh - th)));
//        Log.d("zwt", "double Tab scale - " + scale);
//        Log.d("zwt", "double Tab maxFitScale - " + maxFitScale);
//        Log.d("zwt", "double Tab minFitScale - " + minFitScale);
//        Log.d("zwt", "double Tab destScale - " + destScale);
        Log.d("zwt", "doubleTapScale");
        scaleTo(cx, cy, destScale, smooth, smoothTime);
    }

    @Override
    public void scaleToFitViewMax(int cx, int cy, boolean smooth, long smoothTime) {
        Log.d("zwt", "scaleToFitViewMax");
        scaleTo(cx, cy, getMaxFitViewScaleFactor(), smooth, smoothTime);
    }

    @Override
    public void scaleToFitViewMin(int cx, int cy, boolean smooth, long smoothTime) {
        Log.d("zwt", "scaleToFitViewMin");
        scaleTo(cx, cy, getMinFitViewScaleFactor(), smooth, smoothTime);
    }

    @Override
    public boolean isNotAvailable() {
        return (sIsSettingImage ||
                (mSourceBitmap == null && mBitmapRegionDecoder == null) ||
                mOriginImageRect.width() <= 0 || mOriginImageRect.height() <= 0);
    }

    @Override
    public boolean isTapOnImage(int x, int y) {
        return !isNotAvailable() && toViewCoordinate(mShowBitmapRect).contains(x, y);
    }

    @Override
    public boolean isSettingImage() {
        return sIsSettingImage;
    }

    @Override
    public Rect getRealImageRect() {
        return mOriginImageRect;
    }

    @Override
    public Rect getCurImageRect() {
        return new Rect(0, 0, (int) mShowBitmapRect.width(), (int) mShowBitmapRect.height());
    }

    @Override
    public float getCurScaleFactor() {
        if (isNotAvailable()) {
            return 0;
        }

        return mShowBitmapRect.height() * 1f / mOriginImageRect.height();
    }

    @Override
    public void updateSampleSize() {
        if (isNotAvailable()) {
            return;
        }

        int sampleSize = getCurSampleSize();
        if (sampleSize == mSampleSize) {
            return;
        }
        mSampleSize = sampleSize;
        mPhotoView.callPostInvalidate();
    }

    @Override
    public void destroy() {
        if (mLoadingThread != null) {
            mLoadingThread.quit();
        }
        mLoadingThread = null;
        if (mSourceBitmapCacheFile != null) {
            mSourceBitmapCacheFile.delete(); // 删除临时文件
        }
        recycleAll();

        mPhotoView.callPostInvalidate();
    }

    /**
     * 将原图解析出一块 bitmap
     */
    private Bitmap decodeRectBitmap(Rect rect, int sampleSize) {
        if (rect == null || !mOriginImageRect.contains(rect)) {
            return null;
        }

        synchronized (mDecodeSyncLock) {
            if (mSourceBitmap != null) {
                try {
                    checkRectSize(rect);
                    return Bitmap.createBitmap(mSourceBitmap, rect.left, rect.top, rect.width(), rect.height());
                } catch (OutOfMemoryError exp) {
                    mPhotoView.onSetImageFinished(null, false, null);
                    return null;
                }
            } else if (mBitmapRegionDecoder != null && !mBitmapRegionDecoder.isRecycled()) {
                BitmapFactory.Options tmpOptions = new BitmapFactory.Options();
                tmpOptions.inPreferredConfig = mBitmapConfig;
                tmpOptions.inSampleSize = sampleSize;
                tmpOptions.inJustDecodeBounds = false;

                return mBitmapRegionDecoder.decodeRegion(rect, tmpOptions);
            }
        }

        return null;
    }

    private void checkRectSize(Rect rect) {
        if (rect.right > mSourceBitmap.getWidth()) {
            rect.right -= rect.right - mSourceBitmap.getWidth();
        }

        if (rect.bottom > mSourceBitmap.getHeight()) {
            rect.bottom -= rect.bottom - mSourceBitmap.getHeight();
        }
    }

    /**
     * BitmapUnit 是将 Bitmap 分割为 N * M 个方块后的单个方块单元
     * - 长期持有当前方块的初始化 bitmap 方便在渲染时提供占用内存较小的 bitmap
     * - 渲染时优先级  mInitiatedThumbBitmap -> mBitmap
     * - 若 mBitmap 不存在或 SampleSize 与当前全局 SampleSize 不符则重新 decode
     */
    private class BitmapUnit {
        /**
         * 是否正在加载 bitmap
         */
        private boolean mIsLoading = false;

        /**
         * 当前 bitmap 的 SampleSize
         * 如果此时的 SampleSize 和全局的 SampleSize 不相等，则需要重新 decode 一次
         */
        int mCurSampleSize = 0;

        /**
         * 目前的 mBitmap
         */
        Bitmap mBitmap = null;

        /**
         * 初始化时的缩略图 bitmap
         */
        Bitmap mInitiatedThumbBitmap = null;

        /**
         * 这里回收所有的 bitmap
         */
        private void recycleMemory() {
            mBitmap = null;
            mInitiatedThumbBitmap = null;
            mCurSampleSize = 0;
        }

        /**
         * 这里只回收正常的 bitmap, 不回收缩略图的 bitmap
         */
        private void recycle() {
            mBitmap = null;
            mCurSampleSize = mThumbSampleSize;
        }
    }

    /**
     * 图片网格化解析策略
     * 初始化时将原图以 View 尺寸切割为 N * M 个方块并解析为 N * M 个缩略图保存在 mGrids中
     */
    private class BitmapGridStrategy {

        /**
         * 总共的单元格数
         */
        private int mGridHeight = 0;
        private int mGridWidth = 0;

        /**
         * 所有的单元格
         */
        private BitmapUnit[][] mGrids = null;

        private void initializeBitmapGrid() {
            if (mGrids != null) {
                recycleAllGrids();
            }

            int vw = mOriginViewRect.width();
            int vh = mOriginViewRect.height();

            int iw = mOriginImageRect.width();
            int ih = mOriginImageRect.height();

            // 以原图为蓝本，View 为可视窗口进行蓝本切割 （ / 加 % 的完整倍数，不满 1 的以 1 计）
            mGridHeight = ih / vh + (ih % vh == 0 ? 0 : 1);
            mGridWidth = iw / vw + (iw % vw == 0 ? 0 : 1);

            mGrids = new BitmapUnit[mGridHeight][mGridWidth];
            for (int i = 0; i < mGridHeight; ++i) {
                for (int j = 0; j < mGridWidth; ++j) {
                    mGrids[i][j] = new BitmapUnit();
                    mGrids[i][j].mCurSampleSize = mSampleSize;
                }
            }

            // 打印行列数
            Log.d(TAG, "Grid row " + mGrids.length);
            if (mGrids.length > 0) {
                Log.d(TAG, "Grid column " + mGrids[0].length);
            }

            // 异步加载缩略图
            if (mLoadingThread.isAlive()) {
                mLoadingHandler.post(mDecodeThumbRunnable);
            }
        }

        Runnable mDecodeThumbRunnable = new Runnable() {
            @Override
            public void run() {
                decodeThumbUnitBitmap();
                onSetImageFinished(true);
            }
        };

        /**
         * 解码为缩略图的 bitmap
         */
        private void decodeThumbUnitBitmap() {
            for (int n = 0; n < mGridHeight; ++n) {
                for (int m = 0; m < mGridWidth; ++m) {
                    Rect rect = getImageUnitRect(n, m);
                    if (rect != null) {
                        mGrids[n][m].mCurSampleSize = mSampleSize;
                        mGrids[n][m].mInitiatedThumbBitmap = decodeRectBitmap(rect, mGrids[n][m].mCurSampleSize);
                    }
                }
            }
        }

        /**
         * 获取 bitmap
         */
        private Bitmap getGridBitmap(final int n, final int m) {
            if (isValidGrid(n, m)) {
                BitmapUnit unit = mGrids[n][m];
                if (mSourceBitmap != null) {
                    return unit.mInitiatedThumbBitmap;
                }

                if (mSampleSize == mThumbSampleSize) {
                    return unit.mInitiatedThumbBitmap;
                }

                if (unit.mCurSampleSize != mSampleSize) {
                    loadUnitBitmap(n, m);
                }

                return (unit.mBitmap != null && !unit.mBitmap.isRecycled()) ? unit.mBitmap : unit.mInitiatedThumbBitmap;
            }

            return null;
        }

        /**
         * 异步就加载单元格 bitmap
         */
        private void loadUnitBitmap(final int n, final int m) {
            if (mSampleSize != mThumbSampleSize && isValidGrid(n, m)) {
                BitmapUnit unit = mGrids[n][m];
                if (unit.mIsLoading) {
                    return;
                }
                unit.mIsLoading = true;

                mLoadingHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isValidGrid(n, m)) {
                            decodeVisibleUnitBitmap(n, m);
                            mGrids[n][m].mIsLoading = false;
                            if (mGrids[n][m].mCurSampleSize != mSampleSize) {
                                return;
                            }
                            mPhotoView.callPostInvalidate();
                        }
                    }
                });
            }
        }

        /**
         * 回收所有的单元格
         */
        private void recycleAllGrids() {
            for (int i = 0; i < mGridHeight; ++i) {
                for (int j = 0; j < mGridWidth; ++j) {
                    mGrids[i][j].recycleMemory();
                }
            }
        }

        /**
         * 判断是否为有效的单元格
         */
        private boolean isValidGrid(int n, int m) {
            return n >= 0 && n < mGridHeight && m >= 0 && m < mGridWidth;
        }

        /**
         * 得出原图的单元格
         */
        private Rect getImageUnitRect(int n, int m) {
            if (n < 0 || n >= mGridHeight || m < 0 || m >= mGridWidth) {
                return null;
            }

            int vw = mOriginViewRect.width();
            int vh = mOriginViewRect.height();

            int iw = mOriginImageRect.width();
            int ih = mOriginImageRect.height();

            int left = Math.min(iw, m * vw);
            int right = Math.min(iw, left + vw);

            int top = Math.min(ih, n * vh);
            int bottom = Math.min(ih, top + vh);

            if (left == right || top == bottom) {
                return null;
            }

            return new Rect(left, top, right, bottom);
        }

        /**
         * 获取显示的单元格 rect
         * 原始图坐标系
         */
        private Rect getShowBitmapUnit(int n, int m) {
            // 取 height 或 width 都可以，等比的
            float bitmapRatio = mShowBitmapRect.height() * 1f / mOriginImageRect.height();
            RectF vRect = XPhotoViewUtil.rectMulti(mOriginViewRect, bitmapRatio);

            float vw = vRect.width();
            float vh = vRect.height();

            float sWidth = mShowBitmapRect.width();
            float sHeight = mShowBitmapRect.height();

            float left = Math.min(m * vw, sWidth);
            float right = Math.min(left + vw, sWidth);

            float top = Math.min(n * vh, sHeight);
            float bottom = Math.min(top + vh, sHeight);

            return new Rect((int) left, (int) top, (int) right, (int) bottom);
        }

        /**
         * 判断是否是可见的单元格
         */
        private boolean isVisibleGrid(int n, int m) {
            Rect v = getVisibleGrid();

            return n >= v.top && n <= v.bottom && m >= v.left && m <= v.right;
        }

        /**
         * 回收不可见区域的bitmap
         *
         * @param visible 可见区域
         */
        private void recycleInvisibleGrids(Rect visible) {
            if (mGrids == null) {
                return;
            }

            int sn = visible.top;
            int sm = visible.left;
            int en = visible.bottom;
            int em = visible.right;

            /**
             * 如果上一次有不可见的，并距离可见区域 > 1 的，就释放掉
             * +--+--+--+--+--+
             * |XX|XX|11|11|XX|
             * +--+--+--+--+--+
             * |XX|XX|11|11|XX|
             * +--+--+--+--+--+
             * |XX|XX|XX|XX|XX|
             * +--+--+--+--+--+
             * XX 部分就是可以被释放掉的区域
             */
            int mn = 1;
            for (int i = 0; i < mGridHeight; ++i) {
                for (int j = 0; j < mGridWidth; ++j) {
                    if (sn - i >= mn || i - en >= mn || sm - j >= mn || j - em >= mn) {
                        mGrids[i][j].recycle();
                    }
                }
            }
        }

        /**
         * 画出可见的几个格子
         */
        private boolean drawVisibleGrid(Canvas canvas) {
            if ((mSourceBitmap == null && mBitmapRegionDecoder == null) ||
                    mGrids == null || mOriginImageRect.width() <= 0 || mOriginImageRect.height() <= 0) {
                return false;
            }

            // 获取可见格子所占的行列信息
            Rect visibleGridDeterminant = getVisibleGrid();
            recycleInvisibleGrids(visibleGridDeterminant);

            int sn = visibleGridDeterminant.top;
            int sm = visibleGridDeterminant.left;
            int en = visibleGridDeterminant.bottom;
            int em = visibleGridDeterminant.right;

            for (int n = sn; n <= en; ++n) {
                for (int m = sm; m <= em; ++m) {
                    Rect rect = getShowBitmapUnit(n, m);
                    Bitmap bitmap = getGridBitmap(n, m);
                    if (bitmap != null) {
                        Rect vRect = toViewCoordinate(rect);
                        canvas.drawBitmap(bitmap, null, vRect, null);
                    }
                }
            }

            return true;
        }

        /**
         * decode 出一个可见单元的 bitmap
         * 并保存这个 bitmap 的 sample size
         */
        private synchronized void decodeVisibleUnitBitmap(int n, int m) {
            if (isValidGrid(n, m) && isVisibleGrid(n, m)) {
                BitmapUnit unit = mGrids[n][m];

                // 防止二次decode
                if (unit.mCurSampleSize == mSampleSize) {
                    return;
                }

                unit.recycle();

                Rect rect = getImageUnitRect(n, m);
                unit.mCurSampleSize = mSampleSize;
                unit.mBitmap = decodeRectBitmap(rect, unit.mCurSampleSize);
            }
        }

        /**
         * 计算出可见的实际单元格的行列信息 start - end，存入 Rect 是为了存储方便
         * 即计算从原图抠下来的那块 Bitmap 所涉及的单元格行列信息
         *
         * @return Rect (left=sm, top=sn, right=em, bottom=en)
         */
        private Rect getVisibleGrid() {
            // 计算图片压缩比然后将可视部分 rect 转换到原图坐标上，得到原图需要显示的区域 vBRect
            float ratio = mOriginImageRect.height() / mShowBitmapRect.height();
            RectF vBRect = XPhotoViewUtil.rectMulti(getVisibleShowBitmapRect(), ratio);

            // 因为 Grid 是将原图以 ViewRect 切割，于是用 ViewRect 再次计算可见 Grid 坐标
            int sm = (int) (vBRect.left / mOriginViewRect.width());
            int sn = (int) (vBRect.top / mOriginViewRect.height());

            int em = (int) (sm + Math.ceil(vBRect.width() / mOriginViewRect.width()));
            int en = (int) (sn + Math.ceil(vBRect.height() / mOriginViewRect.height()));

            em = Math.min(em, mGridWidth);
            en = Math.min(en, mGridHeight);
            return new Rect(sm, sn, em, en);
        }
    }

    /***
     * 返回 mViewBitmapRect，左右上下最大值不超过 mShowBitmapRect
     * @return
     */
    private Rect getVisibleShowBitmapRect() {
        int left = (int) Math.max(mShowBitmapRect.left, mShowBitmapViewRect.left);
        int right = (int) Math.min(mShowBitmapRect.right, mShowBitmapViewRect.right);
        int top = (int) Math.max(mShowBitmapRect.top, mShowBitmapViewRect.top);
        int bottom = (int) Math.min(mShowBitmapRect.bottom, mShowBitmapViewRect.bottom);

        return new Rect(left, top, right, bottom);
    }

    /**
     * 坐标转换, bitmap 坐标转换为 view 坐标
     */
    private Rect toViewCoordinate(Rect rect) {
        if (rect == null) {
            return new Rect();
        }

        int left = rect.left - mShowBitmapViewRect.left;
        int right = left + rect.width();
        int top = rect.top - mShowBitmapViewRect.top;
        int bottom = top + rect.height();

        return new Rect(left, top, right, bottom);
    }

    /**
     * 坐标转换，传入的 Rect 相对于 ShowBitmapViewRect 的坐标信息
     * 本项目中传入的 rect 为 Bitmap 的 rect，其 rect.left 固定为 0
     * @param rect
     * @return
     */
    private RectF toViewCoordinate(RectF rect) {
        if (rect == null) {
            return new RectF();
        }

        float left = rect.left - mShowBitmapViewRect.left;
        float right = left + rect.width();
        float top = rect.top - mShowBitmapViewRect.top;
        float bottom = top + rect.height();

        return new RectF(left, top, right, bottom);
    }
}
