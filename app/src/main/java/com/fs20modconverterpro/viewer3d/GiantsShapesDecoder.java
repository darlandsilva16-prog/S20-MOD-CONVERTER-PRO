package com.fs20modconverterpro.viewer3d;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Leitor limitado dos contêineres .i3d.shapes das versões 2 a 7. */
public final class GiantsShapesDecoder {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_VERTICES = 250_000, MAX_FACES = 150_000, MAX_ENTITIES = 20_000;
    private static final int MAX_TOTAL_VERTICES = 500_000, MAX_TOTAL_FACES = 150_000;
    public ShapesFile decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_BYTES) throw new IOException("Arquivo .i3d.shapes vazio ou acima de 32 MiB.");
        int b0=bytes[0]&255,b1=bytes[1]&255,b2=bytes[2]&255,b3=bytes[3]&255;
        int version, seed;
        if (b0 >= 4) { version=b0; seed=b2; } else if (b3==2 || b3==3) { version=b3; seed=b1; } else throw new IOException("Cabeçalho .i3d.shapes desconhecido.");
        if (version < 2 || version > 7) throw new IOException("Versão .i3d.shapes " + version + " não suportada; leitor comprovado para 2 a 7.");
        byte[] payload=new byte[bytes.length-4]; System.arraycopy(bytes,4,payload,0,payload.length);
        Cipher cipher=new Cipher(seed); Cursor framed=new Cursor(payload,version>=4?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        int count=framed.i32(cipher); if(count<0||count>MAX_ENTITIES) throw new IOException("Quantidade de entidades .i3d.shapes inválida.");
        ArrayList<Shape> shapes=new ArrayList<>(); int totalVertices=0,totalFaces=0;
        for(int e=0;e<count;e++) {
            int type=framed.i32(cipher), size=framed.i32(cipher);
            if(size<0||size>framed.remaining()||size>MAX_BYTES) throw new IOException("Tamanho de entidade .i3d.shapes inválido.");
            byte[] data=framed.bytes(size,cipher);
            if(type==1) {
                Shape shape=readShape(data,version);
                totalVertices+=shape.positions.length; totalFaces+=shape.indices.length/3;
                if(totalVertices>MAX_TOTAL_VERTICES||totalFaces>MAX_TOTAL_FACES) throw new IOException("O conjunto de formas excede os limites seguros de memória ou renderização.");
                shapes.add(shape);
            }
        }
        return new ShapesFile(version,seed,shapes);
    }
    Shape readShape(byte[] data,int version)throws IOException {
        Cursor c=new Cursor(data,version>=4?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        int nameLen=c.i32(); if(nameLen<0||nameLen>4096||nameLen>c.remaining()) throw new IOException("Nome de forma inválido.");
        String name=new String(c.bytes(nameLen),StandardCharsets.US_ASCII); c.align(4); long id=c.u32();
        for(int i=0;i<4;i++) c.f32(); // limite espacial do contêiner
        int corners=c.count(MAX_FACES*3,"cantos"), subsets=c.count(4096,"grupos"), vertices=c.count(MAX_VERTICES,"vértices"), options=c.i32();
        if(corners%3!=0) throw new IOException("A malha contém quantidade inválida de índices.");
        ArrayList<Subset> groups=new ArrayList<>();
        for(int i=0;i<subsets;i++){int fv=c.count(vertices,"vértice inicial"),nv=c.count(vertices,"vértices do grupo"),fi=c.count(corners,"índice inicial"),ni=c.count(corners,"índices do grupo");if(fv+nv>vertices||fi+ni>corners)throw new IOException("Grupo de polígonos fora dos limites da malha.");if(version>=6)for(int bit=1;bit<=8;bit<<=1)if((options&bit)!=0)c.f32();groups.add(new Subset(fv,nv,fi,ni));}
        int[] indices=new int[corners];
        for(int i=0;i<corners;i++){indices[i]=vertices>65536?c.i32():c.u16();if(indices[i]<0||indices[i]>=vertices)throw new IOException("Índice de vértice fora da malha.");}
        c.align(4);
        float[][] positions=new float[vertices][3];for(int i=0;i<vertices;i++)for(int j=0;j<3;j++)positions[i][j]=c.finiteFloat();
        float[][] normals=null;if((options&1)!=0){normals=new float[vertices][3];for(int i=0;i<vertices;i++)for(int j=0;j<3;j++)normals[i][j]=c.finiteFloat();}
        if(version>=5&&(options&128)!=0)for(int i=0;i<vertices*4;i++)c.finiteFloat();
        int uvCount=0;for(int bit=2;bit<=16;bit<<=1)if((options&bit)!=0){uvCount++;for(int i=0;i<vertices*2;i++)c.finiteFloat();}
        if((options&32)!=0)for(int i=0;i<vertices*4;i++)c.finiteFloat();
        if((options&64)!=0){if((options&256)==0)for(int i=0;i<vertices*4;i++)c.finiteFloat();c.skip(vertices*((options&256)!=0?1:4));}
        if((options&512)!=0)for(int i=0;i<vertices;i++)c.finiteFloat();
        int attachments=c.count(4096,"anexos");for(int i=0;i<attachments;i++){int flags=c.i32();if((flags&4)!=0)for(int j=0;j<3;j++)c.finiteFloat();int n=c.i32();if(n<0||n>c.remaining())throw new IOException("Anexo de forma inválido.");c.skip(n);}
        if(c.remaining()!=0)throw new IOException("A forma contém dados não interpretados; não será renderizada parcialmente.");
        return new Shape(name,id,positions,normals,indices,groups,uvCount,options);
    }
    public static final class ShapesFile { public final int version,seed; public final List<Shape> shapes; ShapesFile(int v,int s,List<Shape> x){version=v;seed=s;shapes=Collections.unmodifiableList(x);} }
    public static final class Shape { public final String name; public final long id; public final float[][] positions,normals; public final int[] indices; public final List<Subset> subsets; public final int uvSets,options; Shape(String n,long i,float[][]p,float[][]no,int[]ix,List<Subset>g,int uv,int o){name=n;id=i;positions=p;normals=no;indices=ix;subsets=Collections.unmodifiableList(g);uvSets=uv;options=o;} }
    public static final class Subset { public final int firstVertex,vertexCount,firstIndex,indexCount; Subset(int a,int b,int c,int d){firstVertex=a;vertexCount=b;firstIndex=c;indexCount=d;} }
    private static final class Cursor {
        final ByteBuffer b; Cursor(byte[] d,ByteOrder o){b=ByteBuffer.wrap(d).order(o);} int remaining(){return b.remaining();}
        byte[] bytes(int n)throws IOException{need(n);byte[] d=new byte[n];b.get(d);return d;} byte[] bytes(int n,Cipher x)throws IOException{byte[] d=bytes(n);x.apply(d);return d;}
        int i32()throws IOException{need(4);return b.getInt();} int i32(Cipher x)throws IOException{byte[] d=bytes(4,x);return ByteBuffer.wrap(d).order(b.order()).getInt();} long u32()throws IOException{return i32()&0xffffffffL;}
        int u16()throws IOException{need(2);return b.getShort()&65535;} float f32()throws IOException{need(4);return b.getFloat();}float finiteFloat()throws IOException{float v=f32();if(!Float.isFinite(v))throw new IOException("A malha contém valor não finito.");return v;}
        int count(int max,String label)throws IOException{long n=u32();if(n>max)throw new IOException("Quantidade de "+label+" excede o limite seguro.");return(int)n;}void align(int n)throws IOException{int skip=(n-b.position()%n)%n;skip(skip);}void skip(int n)throws IOException{need(n);b.position(b.position()+n);}void need(int n)throws IOException{if(n<0||b.remaining()<n)throw new IOException("Arquivo .i3d.shapes truncado.");}
    }
    private static final class Cipher {
        final int[] key=new int[16];long block;Cipher(int seed)throws IOException{int start=seed<<4;if(start+16>GiantsCipherKeys.VALUES.length)throw new IOException("Semente de criptografia .i3d.shapes não suportada.");System.arraycopy(GiantsCipherKeys.VALUES,start,key,0,16);key[8]=key[9]=0;}
        void apply(byte[] data){int blocks=(data.length+63)/64;byte[] padded=new byte[blocks*64];System.arraycopy(data,0,padded,0,data.length);ByteBuffer bb=ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN);int[] words=new int[padded.length/4];for(int i=0;i<words.length;i++)words[i]=bb.getInt();int[] state=key.clone();state[8]=(int)block;state[9]=(int)(block>>>32);
            for(int base=0;base<words.length;base+=16){int[] x=state.clone();for(int r=0;r<10;r++){s1(x,0,12,4,8);s1(x,5,1,9,13);s1(x,10,6,14,2);s1(x,15,11,3,7);s2(x,3,0,1,2);s2(x,4,5,6,7);s1(x,10,9,11,8);s2(x,14,15,12,13);}for(int j=0;j<16;j++)words[base+j]^=key[j]+x[j];block++;state[8]=(int)block;state[9]=(int)(block>>>32);}
            bb.clear();for(int w:words)bb.putInt(w);System.arraycopy(padded,0,data,0,data.length);
        }
        static int rol(int x,int n){return(x<<n)|(x>>>(32-n));}static int ror(int x,int n){return(x>>>n)|(x<<(32-n));}
        static void s1(int[]k,int a,int b,int c,int d){k[c]^=rol(k[b]+k[a],7);k[d]^=rol(k[c]+k[a],9);k[b]^=rol(k[c]+k[d],13);k[a]^=ror(k[b]+k[d],14);}static void s2(int[]k,int a,int b,int c,int d){k[c]^=rol(k[b]+k[a],7);k[d]^=rol(k[b]+k[c],9);k[a]^=rol(k[c]+k[d],13);k[b]^=ror(k[d]+k[a],14);}
    }
}
