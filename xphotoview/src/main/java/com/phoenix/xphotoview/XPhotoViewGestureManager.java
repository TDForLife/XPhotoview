package com.phoenix.xphotoview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;

class XPhotoViewGestureManager extends GestureDetector.SimpleOnGestureListener implements ScaleGestureDetector.OnScaleGestureListener {

    public final static boolean DEBUG = true;
    public final static String TAG = "GestureManager";

    /**
     * 默认双击放大的时间
     */
    private final static int DOUBLE_SCALE_TIME = 400;
    private final static float FLING_VELOCITY = 1.0f;

    private IXPhotoViewLinker mPhotoViewLinker;

    private IXPhotoView mXPhotoView;

    private XGestureDetector mGestureDetector;

    public XPhotoViewGestureManager(Context context, IXPhotoView photoView, IXPhotoViewLinker photoViewLinker) {
        mPhotoViewLinker = photoViewLinker;
        mXPhotoView = photoView;
        mGestureDetector = new XGestureDetector(context, this);
    }

    public boolean onTouchEvent(MotionEvent event) {
        return mGestureDetector.onTouchEvent(event);
    }


    private class XGestureDetector extends GestureDetector {

        // 放大手势检测
        private ScaleGestureDetector mScaleDetector;

        public XGestureDetector(Context context, XPhotoViewGestureManager listener) {
            super(context, listener);

            float density = context.getResources().getDisplayMetrics().density;
            float dpi = density * 160.0f;
            mPhysicalCoeff = SensorManager.GRAVITY_EARTH  * 39.37f * dpi * 3.8f;

            mScaleDetector = new ScaleGestureDetector(context, listener);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            stopFling();
            boolean scaleResult = mScaleDetector.onTouchEvent(event);
            boolean gestureResult = super.onTouchEvent(event);
            return scaleResult || gestureResult;
        }
    }


    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        int x = (int) e.getX();
        int y = (int) e.getY();
        if (DEBUG) {
            Log.e(TAG, "On Tapped: X: " + x + " Y: " + y + " Is: " + (mPhotoViewLinker != null && mPhotoViewLinker.isTapOnImage(x, y)));
        }

        if (mXPhotoView != null) {
            mXPhotoView.onSingleTab();
        }
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mPhotoViewLinker == null) {
            return false;
        }

        int x = (int) e.getX();
        int y = (int) e.getY();
        mPhotoViewLinker.doubleTapScale(x, y, true, DOUBLE_SCALE_TIME);

        return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        if (mXPhotoView != null) {
            mXPhotoView.onLongTab();
        }
    }

    /************************************* 滑动 ****************************************/
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (mPhotoViewLinker == null) {
            return false;
        }

        int state = mPhotoViewLinker.move((int) -distanceX, (int) -distanceY);

        if ((state & XPhotoViewLinker.LEFT) == XPhotoViewLinker.LEFT ||
                (state & XPhotoViewLinker.RIGHT) == XPhotoViewLinker.RIGHT) {
            mXPhotoView.interceptParentTouchEvent(false);
        }

        return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        startFling(velocityX * FLING_VELOCITY, velocityY * FLING_VELOCITY);
        return true;
    }

    /*************************************缩放**************************************/

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        if (mPhotoViewLinker == null) {
            return false;
        }

        float factor = detector.getScaleFactor();
        mPhotoViewLinker.scale(detector.getFocusX(), detector.getFocusY(), factor);

        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        /**
         * 当缩放结束后，计算最新的的SampleSize, 如果SampleSize改变了，则重新解码最新的bitmap
         */
        if (mPhotoViewLinker != null) {
            mPhotoViewLinker.updateSampleSize();
        }
    }


    /********************************** 滑动惯性 *******************************/

    // 物理多项式系数
    private float mPhysicalCoeff;
    // 滑动摩擦
    private float mFlingFriction = ViewConfiguration.getScrollFriction();
    // 减速加速度，根据对数的换底公式推导
    // 此值「可能」为 0.9 衰减为 0.78 的衰减加速度，经计算其值为：2.3582017，自然底数 e，即自然增长的极限，其为 2.71828
    private final static float DECELERATION_RATE = (float) (Math.log(0.78) / Math.log(0.9));
    // 拐点
    private final static float INFLEXION = 0.45f;

    private ValueAnimator mValueAnimator = null;

    private void stopFling() {
        if (mValueAnimator != null) {
            mValueAnimator.cancel();
        }
    }

    private void startFling(final float velocityX, final float velocityY) {
        stopFling();

        final float directionX = (velocityX < 0 ? 1 : -1);
        final float directionY = (velocityY < 0 ? 1 : -1);

        final float velocity = (float) Math.hypot(velocityX, velocityY);
        final long duration = getSplineFlingDuration(velocity);

        mValueAnimator = ValueAnimator.ofFloat(1f, 0);
        mValueAnimator.setInterpolator(new LinearInterpolator());
        mValueAnimator.setDuration(duration);
        mValueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            private Double mLastDisX = Double.NaN;
            private Double mLastDisY = Double.NaN;

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();
                double curDisX = getSplineFlingDistance(value * velocityX) * directionX;
                double curDisY = getSplineFlingDistance(value * velocityY) * directionY;

                if (mLastDisX.isNaN() || mLastDisY.isNaN()) {
                    mLastDisX = curDisX;
                    mLastDisY = curDisY;
                    return;
                }

                int dx = (int) (curDisX - mLastDisX);
                int dy = (int) (curDisY - mLastDisY);

                if (mPhotoViewLinker != null) {
                    mPhotoViewLinker.move(dx, dy);
                }

                mLastDisX = curDisX;
                mLastDisY = curDisY;
            }
        });

        mValueAnimator.start();
    }

    /**
     * 获取减速递减率
     * @param velocity
     * @return
     */
    private double getSplineDeceleration(float velocity) {
        return Math.log(INFLEXION * Math.abs(velocity) / (mFlingFriction * mPhysicalCoeff));
    }

    private int getSplineFlingDuration(float velocity) {
        final double deceleration = getSplineDeceleration(velocity);
        final double decelMinusOne = DECELERATION_RATE - 1;
        return (int) (1000.0 * Math.exp(deceleration / decelMinusOne));
    }

    private double getSplineFlingDistance(float velocity) {
        final double deceleration = getSplineDeceleration(velocity);
        final double decelMinusOne = DECELERATION_RATE - 1;
        return mFlingFriction * mPhysicalCoeff * Math.exp(DECELERATION_RATE / decelMinusOne * deceleration);
    }
}

