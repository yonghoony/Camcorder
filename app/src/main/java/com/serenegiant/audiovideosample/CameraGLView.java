package com.serenegiant.audiovideosample;
/*
 * AudioVideoRecordingSample
 * Sample project to cature audio and video from internal mic/camera and save as MPEG4 file.
 *
 * Copyright (c) 2014-2015 saki t_saki@serenegiant.com
 *
 * File name: CameraGLView.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
*/

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.serenegiant.encoder.MediaVideoEncoder;

/**
 * Sub class of GLSurfaceView to display camera preview and write video frame to capturing surface
 */
public final class CameraGLView extends GLSurfaceView {

	private static final String TAG = "CameraGLView";

	private static final int CAMERA_ID = 0;

	public static final int SCALE_STRETCH_FIT = 0;
	public static final int SCALE_KEEP_ASPECT_VIEWPORT = 1;
	public static final int SCALE_KEEP_ASPECT = 2;
	public static final int SCALE_CROP_CENTER = 3;

	private final CameraSurfaceRenderer mRenderer;
	public boolean hasSurface;
	private CameraHandler mCameraHandler = null;
	public int mVideoWidth, mVideoHeight;
	private int mRotation;
	private int mScaleMode = SCALE_CROP_CENTER;

	public CameraGLView(final Context context) {
		this(context, null, 0);
	}

	public CameraGLView(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CameraGLView(final Context context, final AttributeSet attrs, final int defStyle) {
		super(context, attrs);
		Log.v(TAG, "CameraGLView:");
		mRenderer = new CameraSurfaceRenderer(this);
		setEGLContextClientVersion(2);	// GLES 2.0, API >= 8
		setRenderer(mRenderer);
/*		// the frequency of refreshing of camera preview is at most 15 fps
		// and RENDERMODE_WHEN_DIRTY is better to reduce power consumption
		setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY); */
	}

	@Override
	public void onResume() {
		Log.v(TAG, "onResume:");
		super.onResume();
		if (hasSurface) {
			if (mCameraHandler == null) {
				Log.v(TAG, "surface already exist");
				startPreview(getWidth(),  getHeight());
			}
		}
	}

	@Override
	public void onPause() {
		Log.v(TAG, "onPause:");
		if (mCameraHandler != null) {
			// just request stop prviewing
			mCameraHandler.stopPreview(false);
		}
		super.onPause();
	}

	public void setScaleMode(final int mode) {
		if (mScaleMode != mode) {
			mScaleMode = mode;
			queueEvent(new Runnable() {
				@Override
				public void run() {
					mRenderer.updateViewport();
				}
			});
		}
	}

	public int getScaleMode() {
		return mScaleMode;
	}

	public void setVideoSize(final int width, final int height) {
		if ((mRotation % 180) == 0) {
			mVideoWidth = width;
			mVideoHeight = height;
		} else {
			mVideoWidth = height;
			mVideoHeight = width;
		}
		queueEvent(new Runnable() {
			@Override
			public void run() {
				mRenderer.updateViewport();
			}
		});
	}

	public int getVideoWidth() {
		return mVideoWidth;
	}

	public int getVideoHeight() {
		return mVideoHeight;
	}

	public SurfaceTexture getSurfaceTexture() {
		Log.v(TAG, "getSurfaceTexture:");
		return mRenderer != null ? mRenderer.mSTexture : null;
	}

	@Override
	public void surfaceDestroyed(final SurfaceHolder holder) {
		Log.v(TAG, "surfaceDestroyed:");
		if (mCameraHandler != null) {
			// wait for finish previewing here
			// otherwise camera try to display on un-exist Surface and some error will occure
			mCameraHandler.stopPreview(true);
		}
		mCameraHandler = null;
		hasSurface = false;
		mRenderer.onSurfaceDestroyed();
		super.surfaceDestroyed(holder);
	}

	public void setVideoEncoder(final MediaVideoEncoder encoder) {
		Log.v(TAG, "setVideoEncoder:tex_id=" + mRenderer.hTex + ",encoder=" + encoder);
		queueEvent(new Runnable() {
			@Override
			public void run() {
				synchronized (mRenderer) {
					if (encoder != null) {
						encoder.setEglContext(EGL14.eglGetCurrentContext(), mRenderer.hTex);
					}
					mRenderer.setVideoEncoder(encoder);
				}
			}
		});
	}

//********************************************************************************
//********************************************************************************
	public synchronized void startPreview(final int width, final int height) {
		if (mCameraHandler == null) {
			final CameraThread thread = new CameraThread(this);
			thread.start();
			mCameraHandler = thread.getHandler();
		}
		mCameraHandler.startPreview(width, height);
	}

	/**
	 * Handler class for asynchronous camera operation
	 */
	private static final class CameraHandler extends Handler {
		private static final String TAG = "CameraHandler";
		private static final int MSG_PREVIEW_START = 1;
		private static final int MSG_PREVIEW_STOP = 2;
		private CameraThread mThread;

		public CameraHandler(final CameraThread thread) {
			mThread = thread;
		}

		public void startPreview(final int width, final int height) {
			sendMessage(obtainMessage(MSG_PREVIEW_START, width, height));
		}

		/**
		 * request to stop camera preview
		 * @param needWait need to wait for stopping camera preview
		 */
		public void stopPreview(final boolean needWait) {
			synchronized (this) {
				sendEmptyMessage(MSG_PREVIEW_STOP);
				if (needWait && mThread.mIsRunning) {
					try {
						Log.d(TAG, "wait for terminating of camera thread");
						wait();
					} catch (final InterruptedException e) {
					}
				}
			}
		}

		/**
		 * message handler for camera thread
		 */
		@Override
		public void handleMessage(final Message msg) {
			switch (msg.what) {
			case MSG_PREVIEW_START:
				mThread.startPreview(msg.arg1, msg.arg2);
				break;
			case MSG_PREVIEW_STOP:
				mThread.stopPreview();
				synchronized (this) {
					notifyAll();
				}
				Looper.myLooper().quit();
				mThread = null;
				break;
			default:
				throw new RuntimeException("unknown message:what=" + msg.what);
			}
		}
	}

	/**
	 * Thread for asynchronous operation of camera preview
	 */
	private static final class CameraThread extends Thread {
    	private final Object mReadyFence = new Object();
    	private final WeakReference<CameraGLView>mWeakParent;
    	private CameraHandler mHandler;
    	private volatile boolean mIsRunning = false;
		private Camera mCamera;
		private boolean mIsFrontFace;

    	public CameraThread(final CameraGLView parent) {
			super("Camera thread");
    		mWeakParent = new WeakReference<CameraGLView>(parent);
    	}

    	public CameraHandler getHandler() {
            synchronized (mReadyFence) {
            	try {
            		mReadyFence.wait();
            	} catch (final InterruptedException e) {
                }
            }
            return mHandler;
    	}

    	/**
    	 * message loop
    	 * prepare Looper and create Handler for this thread
    	 */
		@Override
		public void run() {
            Log.d(TAG, "Camera thread start");
            Looper.prepare();
            synchronized (mReadyFence) {
                mHandler = new CameraHandler(this);
                mIsRunning = true;
                mReadyFence.notify();
            }
            Looper.loop();
            Log.d(TAG, "Camera thread finish");
            synchronized (mReadyFence) {
                mHandler = null;
                mIsRunning = false;
            }
		}

		/**
		 * start camera preview
		 * @param width
		 * @param height
		 */
		private final void startPreview(final int width, final int height) {
			Log.v(TAG, "startPreview:");
			final CameraGLView parent = mWeakParent.get();
			if ((parent != null) && (mCamera == null)) {
				// This is a sample project so just use 0 as camera ID.
				// it is better to selecting camera is available
				try {
					mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
					final Camera.Parameters params = mCamera.getParameters();

					final List<String> focusModes = params.getSupportedFocusModes();
					if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
					} else if(focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
					} else {
						Log.i(TAG, "Camera does not support autofocus");
					}
					// let's try fastest frame rate. You will get near 60fps, but your device become hot.
					final List<int[]> supportedFpsRange = params.getSupportedPreviewFpsRange();
//					final int n = supportedFpsRange != null ? supportedFpsRange.size() : 0;
//					int[] range;
//					for (int i = 0; i < n; i++) {
//						range = supportedFpsRange.get(i);
//						Log.i(TAG, String.format("supportedFpsRange(%d)=(%d,%d)", i, range[0], range[1]));
//					}
					final int[] max_fps = supportedFpsRange.get(supportedFpsRange.size() - 1);
					Log.i(TAG, String.format("fps:%d-%d", max_fps[0], max_fps[1]));
					params.setPreviewFpsRange(max_fps[0], max_fps[1]);
					params.setRecordingHint(true);
					// request closest supported preview size
					final Camera.Size closestSize = getClosestSupportedSize(
						params.getSupportedPreviewSizes(), width, height);
					params.setPreviewSize(closestSize.width, closestSize.height);
					// request closest picture size for an aspect ratio issue on Nexus7
					final Camera.Size pictureSize = getClosestSupportedSize(
						params.getSupportedPictureSizes(), width, height);
					params.setPictureSize(pictureSize.width, pictureSize.height);
					// rotate camera preview according to the device orientation
					setRotation(params);
					mCamera.setParameters(params);
					// get the actual preview size
					final Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
					Log.i(TAG, String.format("previewSize(%d, %d)", previewSize.width, previewSize.height));
					// adjust view size with keeping the aspect ration of camera preview.
					// here is not a UI thread and we should request parent view to execute.
					parent.post(new Runnable() {
						@Override
						public void run() {
							parent.setVideoSize(previewSize.width, previewSize.height);
						}
					});
					final SurfaceTexture st = parent.getSurfaceTexture();
					st.setDefaultBufferSize(previewSize.width, previewSize.height);
					mCamera.setPreviewTexture(st);
				} catch (final IOException e) {
					Log.e(TAG, "startPreview:", e);
					if (mCamera != null) {
						mCamera.release();
						mCamera = null;
					}
				} catch (final RuntimeException e) {
					Log.e(TAG, "startPreview:", e);
					if (mCamera != null) {
						mCamera.release();
						mCamera = null;
					}
				}
				if (mCamera != null) {
					// start camera preview display
					mCamera.startPreview();
				}
			}
		}

		private static Camera.Size getClosestSupportedSize(List<Camera.Size> supportedSizes, final int requestedWidth, final int requestedHeight) {
			return Collections.min(supportedSizes, new Comparator<Camera.Size>() {

				private int diff(final Camera.Size size) {
					return Math.abs(requestedWidth - size.width) + Math.abs(requestedHeight - size.height);
				}

				@Override
				public int compare(final Camera.Size lhs, final Camera.Size rhs) {
					return diff(lhs) - diff(rhs);
				}
			});

		}

		/**
		 * stop camera preview
		 */
		private void stopPreview() {
			Log.v(TAG, "stopPreview:");
			if (mCamera != null) {
				mCamera.stopPreview();
		        mCamera.release();
		        mCamera = null;
			}
			final CameraGLView parent = mWeakParent.get();
			if (parent == null) return;
			parent.mCameraHandler = null;
		}

		/**
		 * rotate preview screen according to the device orientation
		 * @param params
		 */
		private final void setRotation(final Camera.Parameters params) {
			Log.v(TAG, "setRotation:");
			final CameraGLView parent = mWeakParent.get();
			if (parent == null) return;

			final Display display = ((WindowManager)parent.getContext()
				.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
			final int rotation = display.getRotation();
			int degrees = 0;
			switch (rotation) {
				case Surface.ROTATION_0: degrees = 0; break;
				case Surface.ROTATION_90: degrees = 90; break;
				case Surface.ROTATION_180: degrees = 180; break;
				case Surface.ROTATION_270: degrees = 270; break;
			}
			// get whether the camera is front camera or back camera
			final Camera.CameraInfo info =
					new android.hardware.Camera.CameraInfo();
				android.hardware.Camera.getCameraInfo(CAMERA_ID, info);

			mIsFrontFace = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
			if (mIsFrontFace) {	// front camera
				Log.d(TAG, "Back Camera");
				degrees = (info.orientation + degrees) % 360;
				degrees = (360 - degrees) % 360;  // reverse
			} else {  // back camera
				Log.d(TAG, "Front Camera");
				degrees = (info.orientation + degrees + 180) % 360;
			}
			// apply rotation setting
			mCamera.setDisplayOrientation(degrees);
			parent.mRotation = degrees;
			// XXX This method fails to call and camera stops working on some devices.
//			params.setRotation(degrees);
		}

	}
}