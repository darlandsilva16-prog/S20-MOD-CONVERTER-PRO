package com.fs20modconverterpro.viewer3d;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fs20modconverterpro.R;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Seletor SAF, análise protegida e visualização real OpenGL ES de modelos suportados. */
public final class ModelViewerActivity extends Activity {
    private static final int OPEN=701;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ModelLoader loader=new ModelLoader(); private final ZipModelCatalog zipCatalog=new ZipModelCatalog();
    private LinearLayout root; private TextView title,details,status; private ProgressBar progress; private ModelGlSurfaceView surface;
    @Override protected void onCreate(Bundle state){super.onCreate(state);buildUi();}
    private void buildUi(){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,12,16,12);root.setBackgroundColor(0xff101820);title=label("VISUALIZADOR 3D PRO",22);root.addView(title);Button open=new Button(this);open.setText("ABRIR MODELO / ZIP");open.setOnClickListener(v->openPicker());root.addView(open);Button reset=new Button(this);reset.setText("CENTRALIZAR / REINICIAR CÂMERA");reset.setOnClickListener(v->{if(surface!=null)surface.resetCamera();});root.addView(reset);progress=new ProgressBar(this);progress.setVisibility(View.GONE);root.addView(progress);ScrollView scroll=new ScrollView(this);details=label("Abra OBJ, STL, GLB, glTF ou I3D. ZIPs podem conter modelos selecionáveis.\n\nOBJ, STL e alguns glTF 2.0 autocontidos são renderizados em 3D; I3D FS19 com .i3d.shapes compatível é renderizado como malha estática; limitações de transformação/material são informadas.",14);scroll.addView(details);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));status=label("Os arquivos são abertos somente para leitura.",13);root.addView(status);setContentView(root);}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(0xffeeeeee);v.setTextSize(size);v.setPadding(4,8,4,8);return v;}
    private void openPicker(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivityForResult(i,OPEN);}catch(ActivityNotFoundException e){status.setText("Seletor de arquivos do Android indisponível.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==OPEN&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();String name=name(uri);progress.setVisibility(View.VISIBLE);status.setText("Lendo arquivo com limite de tamanho seguro…");worker.execute(()->readAndProcess(uri,name));}}
    private String name(Uri uri){try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}}catch(Exception ignored){}return "modelo";}
    private byte[] read(Uri uri,int max)throws Exception{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IllegalArgumentException("Não foi possível abrir o arquivo.");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if((long)out.size()+n>max)throw new IllegalArgumentException("Arquivo acima do limite seguro de " + Formatter.formatFileSize(this,max) + ".");out.write(b,0,n);}return out.toByteArray();}}
    private void readAndProcess(Uri uri,String name){try{ModelFormat format=ModelFormat.fromName(name);int max=format==ModelFormat.ZIP?ZipModelCatalog.MAX_ARCHIVE_BYTES:ModelLoader.MAX_MODEL_BYTES;byte[] bytes=read(uri,max);if(format==ModelFormat.ZIP){List<String> models=zipCatalog.list(bytes);main.post(()->showZipModels(bytes,models));return;}if(format==ModelFormat.I3D){I3dAnalyzer.Report report=new I3dAnalyzer().analyze(bytes,null);main.post(()->showAnalysis(name,Formatter.formatFileSize(this,bytes.length)+"\n\n"+report.summary()+"\n\nArquivo necessário: "+(report.shapesFileReference==null?name+".shapes":report.shapesFileReference)+"\nAbra o I3D dentro de um ZIP que contenha esse arquivo para renderizar a geometria.\n\nReferências encontradas:\n"+report.textureReferences));return;}ModelLoadResult result=loader.load(name,bytes);main.post(()->showModel(name,bytes.length,result));}catch(Exception e){main.post(()->{progress.setVisibility(View.GONE);status.setText("Falha ao abrir: "+(e.getMessage()==null?"arquivo inválido":e.getMessage()));});}}
    private void showZipModels(byte[] archive,List<String> names){progress.setVisibility(View.GONE);if(names.isEmpty()){status.setText("Nenhum OBJ, STL, GLB, glTF ou I3D reconhecido no ZIP.");return;}String[] choices=names.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Modelos encontrados no ZIP").setItems(choices,(d,which)->{String chosen=choices[which];progress.setVisibility(View.VISIBLE);worker.execute(()->{try{byte[] bytes=zipCatalog.read(archive,chosen);if(ModelFormat.fromName(chosen)==ModelFormat.I3D){java.util.HashSet<String> paths=new java.util.HashSet<>(zipCatalog.listPaths(archive));I3dAnalyzer.Report r=new I3dAnalyzer().analyze(bytes,paths);String shapesPath=resolveShapesPath(chosen,r.shapesFileReference);if(!paths.contains(shapesPath))throw new java.io.IOException("Arquivo binário de geometria ausente no ZIP: "+shapesPath);byte[] shapeBytes=zipCatalog.read(archive,shapesPath);GiantsShapesDecoder.ShapesFile decoded=new GiantsShapesDecoder().decode(shapeBytes);MeshData mesh=new I3dMeshAssembler().assemble(r,decoded);ModelLoadResult result=new ModelLoadResult(ModelFormat.I3D,mesh,"Geometria binária I3D interpretada (formato "+decoded.version+").",mesh.getWarnings());main.post(()->showModel(chosen,bytes.length,result));}else{ModelLoadResult r=loader.load(chosen,bytes);main.post(()->showModel(chosen,bytes.length,r));}}catch(Exception e){main.post(()->{progress.setVisibility(View.GONE);status.setText("Não foi possível ler a entrada selecionada: "+e.getMessage());});}});}).setNegativeButton("Cancelar",null).show();}
    private String resolveShapesPath(String i3dPath,String referenced)throws java.io.IOException{String ref=referenced;if(ref==null||ref.trim().isEmpty()){int dot=i3dPath.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".i3d");ref=(dot>=0?i3dPath.substring(0,dot):i3dPath)+".i3d.shapes";}ref=ref.replace('\\','/');String base="";int slash=i3dPath.lastIndexOf('/');if(slash>=0)base=i3dPath.substring(0,slash+1);java.util.ArrayDeque<String> parts=new java.util.ArrayDeque<>();for(String part:(ref.startsWith("/")?ref.substring(1):base+ref).split("/")){if(part.isEmpty()||part.equals("."))continue;if(part.equals("..")){if(parts.isEmpty())throw new java.io.IOException("Referência .i3d.shapes tenta sair da raiz do ZIP.");parts.removeLast();}else parts.addLast(part);}return android.text.TextUtils.join("/",parts);}
    private void showAnalysis(String name,String info){progress.setVisibility(View.GONE);removeSurface();title.setText(name+" · I3D");details.setText(info);status.setText("Análise XML concluída. Selecione o mod em ZIP para localizar e renderizar a geometria binária compatível.");}
    private void showModel(String name,long size,ModelLoadResult result){progress.setVisibility(View.GONE);if(!result.isRenderable()){removeSurface();details.setText(name+" · "+result.getFormat()+"\n"+Formatter.formatFileSize(this,size)+"\n\n"+result.getMessage());status.setText("Este arquivo não possui uma malha compatível com o renderizador.");return;}MeshData m=result.getMesh();float[] min=m.getMinimum(),max=m.getMaximum();String info=name+" · "+result.getFormat()+"\nTamanho: "+Formatter.formatFileSize(this,size)+"\nDimensões aproximadas: "+fmt(max[0]-min[0])+" × "+fmt(max[1]-min[1])+" × "+fmt(max[2]-min[2])+"\nVértices de origem: "+m.getSourceVertices()+"\nFaces triangulares: "+m.getTriangleCount()+"\nMateriais: "+m.getMaterials()+"\nTexturas identificadas: "+m.getTextures()+"\n\n"+result.getMessage()+"\n"+result.getWarnings();details.setText(info);status.setText("Arraste para girar; use dois dedos para zoom e movimentar a câmera. Grade e eixos de referência ativos.");removeSurface();surface=new ModelGlSurfaceView(this);surface.setMesh(m);root.addView(surface,4,new LinearLayout.LayoutParams(-1,0,2));}
    private String fmt(float n){return String.format(java.util.Locale.US,"%.3f",n);}
    private void removeSurface(){if(surface!=null){root.removeView(surface);surface.onPause();surface=null;}}
    @Override protected void onPause(){super.onPause();if(surface!=null)surface.onPause();}
    @Override protected void onResume(){super.onResume();if(surface!=null)surface.onResume();}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
