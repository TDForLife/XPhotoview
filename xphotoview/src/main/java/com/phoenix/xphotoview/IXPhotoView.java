package com.phoenix.xphotoview;

import android.graphics.Rect;

/**
 * XPhotoView 与其他控制器连接器的内部接口，是内部交换数据及控制的桥梁
 * - 接口可以理解成一份协议、一份约束
 * - 这份约束或协议可以是单向的或双向的
 */
interface IXPhotoView {

    enum DoubleTabScale {
        CENTER_CROP(1),
        CENTER_INSIDE(2);
        public int value;
        DoubleTabScale(int v) {
            value = v;
        }
    }

    DoubleTabScale getDoubleTabScale();

    void onSingleTab();

    void onLongTab();

    String getCachedDir();

    void callPostInvalidate();

    void onSetImageFinished(IXPhotoViewLinker bm, boolean success, Rect image);

    void interceptParentTouchEvent(boolean intercept);

    void recycleAll();
}

