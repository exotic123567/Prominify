package com.example.simplesuperresolutionimage

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.icu.text.ListFormatter.Width
import android.Manifest.permission
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.android.identity.util.UUID
import io.getstream.photoview.PhotoView
import com.bumptech.glide.Glide
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class ImageStaging: AppCompatActivity() {
    lateinit var upscalebtn: Button
    lateinit var bitmap: Bitmap
    lateinit var loadingAnim: LottieAnimationView
    lateinit var downloadbtn: Button
    lateinit var outputBitmapdnld: Bitmap
    lateinit var jaggedEdgesbmp: Bitmap
    lateinit var mainsliderval: TextView
    lateinit var mainslider: Slider
    lateinit var imguristr: String
    val writepermcode = 69
    var WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
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
        downloadbtn = findViewById(R.id.downloadbtn)
        downloadbtn.visibility = View.INVISIBLE
        mainsliderval = findViewById(R.id.slidervalue)
        mainslider = findViewById(R.id.slider)
        var debounceJob: Job? = null

        // Google MLKIt code...
        val options =
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()

        val segmenter = Segmentation.getClient(options)

        val selectedImageURIstr = intent.getStringExtra("selectedImageUri")
        if (selectedImageURIstr!=null) {
            imguristr = selectedImageURIstr
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

            val unblurredBitmap = Bitmap.createBitmap(MediaStore.Images.Media.getBitmap(this.contentResolver, Uri.parse(selectedImageURIstr)))
            val blurredBitmap = blurBitmap(this, unblurredBitmap, 25f) // Adjust the radius as needed
            superImgView.setImageBitmap(blurredBitmap)

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

                        var scaledMaskBitmap = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
                        jaggedEdgesbmp = Bitmap.createBitmap(scaledMaskBitmap)
                        var scaledandblurredbmp: Bitmap = blurBitmapincoroutine(scaledMaskBitmap, 5f)

                        processBitmap(scaledandblurredbmp, mutableBitmap)
                        withContext(Dispatchers.Main) {
                            // Process the output tensor and update the UI
                            loadingAnim.visibility = View.GONE
                            loadingAnim.pauseAnimation()
                            val superImgView = findViewById<PhotoView>(R.id.superImgView)
                            superImgView.setImageBitmap(mutableBitmap)
                            //mainslider.visibility = View.VISIBLE
                            //mainsliderval.visibility = View.VISIBLE
                            downloadbtn.visibility = View.VISIBLE
                            upscalebtn.visibility = View.INVISIBLE
                            outputBitmapdnld = Bitmap.createBitmap(mutableBitmap)
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
            }
        }
        downloadbtn.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Default) {
                Log.v("BeforByteStream", "Inside download onclick coroutine")
                //val checkperms = checkStoragePermission()
                //Log.v("CheckPErms", "${checkperms}")

                //requestRuntimePermissions()
                // Permission already granted, proceed with downloading
                saveImage(outputBitmapdnld) {
                    // Handle the saved image path here
                    Log.d("ImageSaved", "Image saved to: $it")
                    Toast.makeText(this@ImageStaging, "IMAGE SAVED", Toast.LENGTH_LONG).show() // to make this working, need to manage coroutine, as this execution is something off the main thread
                }


            }
        }
        /*mainslider.addOnChangeListener { slider, value, fromUser ->
            val currentPosition = value.toFloat()
            mainsliderval.text = currentPosition.toString()
            debounceJob?.cancel()
            debounceJob = lifecycleScope.launch(Dispatchers.Main) {
                delay(250) // Adjust the delay time as needed
                var newbmp = blurBitmap(this@ImageStaging, jaggedEdgesbmp, currentPosition * .25f)
                if (newbmp != null) {
                    //newbmp = processBitmap(newbmp,MediaStore.Images.Media.getBitmap(this@ImageStaging.contentResolver, Uri.parse(imguristr)))
                }
                superImgView.setImageBitmap(newbmp)
            }
        }*/
    }

    private fun requestRuntimePermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this@ImageStaging,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                // You can use the API that requires the permission.
                Log.d("PermGrant", "Permission already granted...")
                return
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this@ImageStaging, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                val builder = AlertDialog.Builder(this@ImageStaging)
                builder.setMessage("Permission to write is required to download the image to your storage...")
                builder.setTitle("Permission Required").setCancelable(false).setPositiveButton("OK", DialogInterface.OnClickListener { dialog, which ->
                    ActivityCompat.requestPermissions(this@ImageStaging, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), writepermcode)
                    dialog.dismiss()
                })
                builder.setNegativeButton("Cancel", DialogInterface.OnClickListener { dialog, which ->
                    dialog.dismiss()
                })
                builder.show()

            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                ActivityCompat.requestPermissions(this@ImageStaging, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), writepermcode)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            writepermcode -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Permission is granted. Continue the action or workflow
                    // in your app.
                    Toast.makeText(this@ImageStaging, "Permission Granted!", Toast.LENGTH_SHORT).show()
                } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    // Explain to the user that the feature is unavailable because
                    // the feature requires a permission that the user has denied.
                    // At the same time, respect the user's decision. Don't link to
                    // system settings in an effort to convince the user to change
                    // their decision.
                    val builder = AlertDialog.Builder(this@ImageStaging)
                    builder.setMessage("Permission to write is required to download the image to your storage...")
                    builder.setTitle("Permission Required").setCancelable(false).setPositiveButton("OK", DialogInterface.OnClickListener { dialog, which ->
                        val intent: Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri: Uri = Uri.fromParts("package", packageName, null)
                        intent.setData(uri)
                        startActivity(intent)
                        dialog.dismiss()
                    })
                    builder.setNegativeButton("Cancel", DialogInterface.OnClickListener { dialog, which ->
                        dialog.dismiss()
                    })
                    builder.show()
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
                requestRuntimePermissions()
            }
        }
    }


    private fun blurBitmapincoroutine(bitmap: Bitmap, radius: Float): Bitmap {
        val rs = RenderScript.create(this)
        val input = Allocation.createFromBitmap(rs, bitmap)
        val output = Allocation.createTyped(rs,
        input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

        script.setRadius(radius)
        script.setInput(input)
        script.forEach(output)
        output.copyTo(bitmap)
        rs.destroy()
        return bitmap    }

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

    fun processBitmap(scaledandblurredbmp: Bitmap, mutableBitmap: Bitmap): Bitmap {
        for (y in 0 until scaledandblurredbmp.height) {
            for (x in 0 until scaledandblurredbmp.width) {
                val maskPixel = scaledandblurredbmp.getPixel(x, y)
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
        return mutableBitmap
    }
    fun saveImage(image: Bitmap, url:(String) -> Unit): String? {
        var savedImagePath: String? = null
        val imageFileName = "PNG_" + UUID.randomUUID().toString() + ".png"
        val storageDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .toString() + "/bgremoverprivate"
        )
        var success = true
        if (!storageDir.exists()) {
            success = storageDir.mkdirs()
        }
        if (success) {
            val imageFile = File(storageDir, imageFileName)
            savedImagePath = imageFile.absolutePath
            try {
                val fOut: OutputStream = FileOutputStream(imageFile)
                image.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Add the image to the system gallery
            url(savedImagePath)
            galleryAddPic(savedImagePath)
        }
        return savedImagePath
    }

    private fun galleryAddPic(imagePath: String?) {
        imagePath?.let { path ->
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val f = File(path)
            val contentUri: Uri = Uri.fromFile(f)
            mediaScanIntent.data = contentUri
            sendBroadcast(mediaScanIntent)
        }
    }
}