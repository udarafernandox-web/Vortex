package com.vortex.downloader

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import java.net.URL

class MainActivity: Activity() {
 private val bg=Color.rgb(7,6,20)
 private val surface=Color.rgb(24,21,47)
 private val accent=Color.rgb(188,116,255)
 private lateinit var input:EditText
 private lateinit var status:TextView
 private lateinit var history:LinearLayout
 private val prefs by lazy { getSharedPreferences("vortex", MODE_PRIVATE) }
 private var autoStart=false
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun shape(c:Int)=GradientDrawable().apply { setColor(c);cornerRadius=dp(18).toFloat() }
 private fun label(t:String,size:Float=15f)=TextView(this).apply { text=t;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER }
 private fun gap(n:Int)=Space(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(n)) }
 override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);autoStart=prefs.getBoolean("auto",false);render();handleShare(intent) }
 override fun onNewIntent(intent:Intent){ super.onNewIntent(intent);setIntent(intent);handleShare(intent) }
 override fun onResume(){super.onResume();Handler(Looper.getMainLooper()).postDelayed({ clipboard(false) },250)}
 private fun button(parent:LinearLayout,t:String,fn:()->Unit){ val b=label(t).apply{background=shape(surface);setPadding(dp(12),dp(16),dp(12),dp(16));setOnClickListener{fn()}};parent.addView(b,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)}) }
 private fun render(){
   val sc=ScrollView(this).apply { setBackgroundColor(bg);fillViewport=true }
   val root=LinearLayout(this).apply{orientation=1;setPadding(dp(22),dp(28),dp(22),dp(28))}
   sc.addView(root);setContentView(sc)
   root.addView(label("VORTEX",34f).apply{typeface=Typeface.DEFAULT_BOLD})
   root.addView(label("Paste. Detect. Download.",14f).apply{setTextColor(accent)})
   root.addView(gap(30))
   input=EditText(this).apply{hint="Paste direct HTTPS media file link";setHintTextColor(0xff9290a6.toInt());setTextColor(-1);isSingleLine=true;background=shape(surface);setPadding(dp(14),dp(16),dp(14),dp(16));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
   root.addView(input,LinearLayout.LayoutParams(-1,-2));root.addView(gap(10))
   button(root,"PASTE CLIPBOARD"){clipboard(true)}
   val v=VortexView(this);root.addView(v,LinearLayout.LayoutParams(-1,dp(235)));v.setOnClickListener{download()}
   root.addView(label("TAP VORTEX TO DOWNLOAD",14f).apply{setTextColor(accent)})
   root.addView(gap(18));status=label("Ready for direct file URLs",14f);root.addView(status);root.addView(gap(26))
   button(root,"OPEN DOWNLOADS"){startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))}
   button(root,"AUTO-DOWNLOAD: "+if(autoStart)"ON" else "OFF"){autoStart=!autoStart;prefs.edit().putBoolean("auto",autoStart).apply();render()}
   button(root,"ABOUT SUPPORTED LINKS"){AlertDialog.Builder(this).setTitle("VORTEX prototype").setMessage("Only direct HTTPS links ending in a common file extension can be queued. Social-media page links, DRM and platform-protected videos are not supported. Clipboard detection happens when the app is open.").setPositiveButton("OK",null).show()}
   root.addView(label("RECENT DOWNLOADS",18f));history=LinearLayout(this).apply{orientation=1};root.addView(history);loadHistory()
 }
 private fun handleShare(i:Intent?) {if(i?.action==Intent.ACTION_SEND && i.type=="text/plain"){val s=i.getStringExtra(Intent.EXTRA_TEXT) ?: return; val url=Regex("https://\\S+").find(s)?.value?:return;input.setText(url);status.text="Shared link detected";if(autoStart&&direct(url))download()}}
 private fun clipboard(force:Boolean){val clip=(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?:return;val s=clip.getItemAt(0)?.coerceToText(this)?.toString()?:return;val u=Regex("https://\\S+").find(s)?.value?:return;if(force||input.text.isBlank()){input.setText(u);status.text="Clipboard link detected";if(!force&&autoStart&&direct(u))download()}}
 private fun direct(url:String):Boolean=try{ URL(url).protocol=="https" && Regex("(?i)\\.(mp4|m4v|webm|mp3|m4a|wav|aac|jpg|jpeg|png|webp|gif|pdf|zip)(?:[?#]|$)").containsMatchIn(url) }catch(e:Exception){false}
 private fun download(){
   val url=input.text.toString().trim()
   if(!direct(url)){status.text="Direct HTTPS media file links only";return}
   try{
     val uri=Uri.parse(url)
     val filename=uri.lastPathSegment?.substringBefore("?")?.takeIf{it.length in 3..90} ?: "vortex-file"
     val req=DownloadManager.Request(uri).apply{setTitle(filename);setDescription("VORTEX download");setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"VORTEX/$filename")}
     val id=(getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
     prefs.edit().putString("file_$id",filename).apply()
     status.text="Queued: $filename";loadHistory()
   }catch(e:Exception){status.text="Download error: ${e.message}"}
 }
 private fun loadHistory(){
  history.removeAllViews()
  prefs.all.filterKeys{it.startsWith("file_")}.entries.takeLast(12).reversed().forEach { e->
   val b=label("↓  ${e.value}",14f).apply{gravity=Gravity.START;background=shape(surface);setPadding(dp(16),dp(15),dp(16),dp(15))}
   history.addView(b,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
  }
 }
 class VortexView(ctx:Context):View(ctx){
  private val paint=Paint(3)
  private var angle=0f
  override fun onDraw(c:Canvas){
   super.onDraw(c)
   val cx=width/2f;val cy=height/2f;val radius=Math.min(width,height)*.37f
   for(i in 0..4){
    val rect=RectF(cx-radius+i*8,cy-radius+i*8,cx+radius-i*8,cy+radius-i*8)
    paint.style=Paint.Style.STROKE;paint.strokeWidth=dpv(6f);paint.strokeCap=Paint.Cap.ROUND;paint.color=Color.HSVToColor(floatArrayOf((270+i*22f)%360, .8f,1f))
    c.drawArc(rect,angle+i*65f,230f,false,paint)
   }
   paint.style=Paint.Style.FILL;paint.color=Color.WHITE;paint.textSize=dpv(46f);paint.textAlign=Paint.Align.CENTER
   c.drawText("↓",cx,cy+dpv(15f),paint)
   angle=(angle+2f)%360;postInvalidateDelayed(25)
  }
  private fun dpv(n:Float)=n*resources.displayMetrics.density
 }
}