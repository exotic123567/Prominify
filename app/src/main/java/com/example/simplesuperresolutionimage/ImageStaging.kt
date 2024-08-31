package com.example.simplesuperresolutionimage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.icu.text.ListFormatter.Width
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import io.getstream.photoview.PhotoView
import com.bumptech.glide.Glide
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.IOException

class ImageStaging: AppCompatActivity() {
    lateinit var upscalebtn: Button
    lateinit var bitmap: Bitmap
    lateinit var loadingAnim: LottieAnimationView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.apply {
            // Hide both the navigation bar and the status bar.
            // SYSTEM_UI_FLAG_FULLSCREEN is only available on Android 4.1 and higher, but as
            // a general rule, you should design your app to hide the status bar whenever you
            // hide the navigation bar.
            systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        enableEdgeToEdge()
        setContentView(R.layout.primary_image_sui)

        upscalebtn = findViewById(R.id.upscalebtn)
        val superImgView = findViewById<PhotoView>(R.id.superImgView)
        loadingAnim = findViewById(R.id.aianim)


        var imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(512, 512, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        // Google MLKIt code...
        val options =
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()

        val segmenter = Segmentation.getClient(options)

        val selectedImageURIstr = intent.getStringExtra("selectedImageUri")
        if (selectedImageURIstr!=null) {
            val selectedImageURI = Uri.parse(selectedImageURIstr)
            Glide.with(this)
                .load(selectedImageURI)
                .into(superImgView)
            bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, selectedImageURI)
        }
        else {
            Toast.makeText(this,"Image Selection failed...", Toast.LENGTH_LONG).show()
        }

        // Google MLKit code...
        //val inputimage = InputImage.fromBitmap(bitmap, 0)
        val image: InputImage = InputImage.fromFilePath(this, Uri.parse(selectedImageURIstr))
        bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, Uri.parse(selectedImageURIstr))

        val PROBABILITY_THRESHOLD = 0.9f
        upscalebtn.setOnClickListener {
            loadingAnim.visibility = View.VISIBLE
            loadingAnim.playAnimation()

            //val blurredBitmap = blurBitmap(this, bitmap, 25f) // Adjust the radius as needed
            //superImgView.setImageBitmap(blurredBitmap)

            fun isMaskTransparent(bmp: Bitmap): Boolean {
                for (y in 0 until bmp.height) {
                    for (x in 0 until bmp.width) {
                        val pxl = bmp.getPixel(x, y)
                        if (pxl != Color.TRANSPARENT) {
                            return false
                        }
                    }
                }
                return true
            }

            lifecycleScope.launch(Dispatchers.Default) {

                val result = segmenter.process(image)
                Log.v("successRes", "val result created...")


                val mutableBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888);
                Log.v("mutableBitmap0", "Base Mutable bitmap generated")
                result.addOnSuccessListener { segmentationMask ->
                    lifecycleScope.launch(Dispatchers.Default) {
                        Log.v("SuccessResult", "Segmenter-model successfully run!")
                        val mask = segmentationMask.buffer
                        val maskWidth = segmentationMask.width
                        val maskHeight = segmentationMask.height

                        Log.v("MaskDimen", "Mask Width : ${maskWidth}\nMask Height : ${maskHeight}")
                        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888);

                        for (y in 0 until maskHeight) {
                            for (x in 0 until maskWidth) {
                                // Gets the confidence of the (x,y) pixel in the mask being in the foreground.
                                val foregroundConfidence = mask.getFloat()
                                if ( foregroundConfidence < PROBABILITY_THRESHOLD) {
                                    mutableBitmap.setPixel(x,y, Color.argb(1, 0, 0, 0))
                                }
                                else {
                                    mutableBitmap.setPixel(x, y, bitmap.getPixel(x, y))
                                }
                            }
                        }

                        for (y in 0 until maskHeight) {
                            for (x in 0 until maskWidth) {
                                maskBitmap.setPixel(x, y, mutableBitmap.getPixel(x,y))
                            }
                        }
                        Log.v("maskdone", "Mask bitmap created successfully of 256 x 256")

                        val scaledMaskBitmap = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)

                        for (y in 0 until scaledMaskBitmap.height) {
                            for (x in 0 until scaledMaskBitmap.width) {
                                val maskPixel = scaledMaskBitmap.getPixel(x, y)
                                val maskAlpha = Color.alpha(maskPixel)
                                val maskRed = Color.red(maskPixel)
                                val maskGreen = Color.green(maskPixel)
                                val maskBlue = Color.blue(maskPixel)

                                val transparentColor = Color.argb(1, 0, 0, 0)
                                val transparentAlpha = Color.alpha(transparentColor)
                                val transparentRed = Color.red(transparentColor)
                                val transparentGreen = Color.green(transparentColor)
                                val transparentBlue = Color.blue(transparentColor)
                                //mutableBitmap.setPixel(x, y, newPixel)
                                if (maskAlpha == transparentAlpha && maskRed == transparentRed && maskGreen == transparentGreen && maskBlue == transparentBlue) {
                                    // The pixel in scaledMaskBitmap is transparent
                                    mutableBitmap.setPixel(x, y, Color.TRANSPARENT)
                                    // ...
                                } else {
                                    val ogPixel = bitmap.getPixel(x, y)
                                    val ogAlpha = Color.alpha(ogPixel)
                                    val ogRed = Color.red(ogPixel)
                                    val ogGreen = Color.green(ogPixel)
                                    val ogBlue = Color.blue(ogPixel)
                                    // The pixel in scaledMaskBitmap is not transparent
                                    mutableBitmap.setPixel(x, y, Color.argb(ogAlpha, ogRed, ogGreen, ogBlue))
                                    // ...
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            // Process the output tensor and update the UI
                            loadingAnim.visibility = View.GONE
                            loadingAnim.pauseAnimation()
                            val superImgView = findViewById<PhotoView>(R.id.superImgView)
                            superImgView.setImageBitmap(mutableBitmap)
                            // ...
                            //model.close()
                            Toast.makeText(this@ImageStaging, "Mock Generation Complete!!!", Toast.LENGTH_SHORT).show()
                        }

                        //val superImgView = findViewById<PhotoView>(R.id.superImgView)
                        //superImgView.setImageBitmap(mutableBitmap)
                    }
                    // Task completed successfully


                    // ... process the results here
                }.addOnFailureListener { e ->
                    // Task failed with an exception
                    // Handle the error
                }



                /*withContext(Dispatchers.Main) {
                    // Process the output tensor and update the UI
                    //loadingAnim.visibility = View.GONE
                    //loadingAnim.pauseAnimation()
                    //superImgView.setImageBitmap(mutableBitmap)
                    // ...
                    //model.close()
                    Toast.makeText(this@ImageStaging, "Mock Generation Complete!!!", Toast.LENGTH_SHORT).show()
                }*/
            }
        }
    }
    private fun cropImage(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height

        val sourceAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
        val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()

        var cropWidth = sourceWidth
        var cropHeight = sourceHeight
        if (sourceAspectRatio > targetAspectRatio) {
            // Image is wider than target
            cropWidth = (sourceHeight * targetAspectRatio).toInt()
        } else {
            // Image is taller than target
            cropHeight = (sourceWidth / targetAspectRatio).toInt()
        }

        val cropX = (sourceWidth - cropWidth) / 2
        val cropY = (sourceHeight - cropHeight) / 2

        return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
    }

    private fun blurBitmap(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        try {
            val rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(radius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(bitmap)
            rs.destroy()
            return bitmap
        } catch (e: Exception) {
            Log.e("BlurUtils", "Error applying blur", e)
            return null
        }
    }
}