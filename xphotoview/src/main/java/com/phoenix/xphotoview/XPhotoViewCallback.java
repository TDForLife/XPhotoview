package com.phoenix.xphotoview;

public class XPhotoViewCallback {

    public interface OnXPhotoLoadListener {
        void onImageLoadStart(XPhotoView view);
        void onImageLoaded(XPhotoView view);
    }
}
