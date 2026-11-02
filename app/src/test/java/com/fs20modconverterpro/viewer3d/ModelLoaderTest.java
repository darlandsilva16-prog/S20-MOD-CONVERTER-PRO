package com.fs20modconverterpro.viewer3d;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class ModelLoaderTest {
    private final ModelLoader loader=new ModelLoader();
    @Test public void identificaFormatosSuportadosEAnalise(){assertEquals(ModelFormat.OBJ,ModelFormat.fromName("trator.OBJ"));assertEquals(ModelFormat.STL,ModelFormat.fromName("x.stl"));assertEquals(ModelFormat.GLB,ModelFormat.fromName("x.glb"));assertEquals(ModelFormat.GLTF,ModelFormat.fromName("x.gltf"));assertEquals(ModelFormat.I3D,ModelFormat.fromName("x.i3d"));}
    @Test public void leObjComPoligonoECalculaDimensoes() throws Exception {String obj="v 0 0 0\nv 2 0 0\nv 2 1 0\nv 0 1 0\nf 1 2 3 4\n";MeshData mesh=loader.load("x.obj",obj.getBytes("UTF-8")).getMesh();assertEquals(2,mesh.getTriangleCount());assertEquals(4,mesh.getSourceVertices());assertEquals(2f,mesh.getMaximum()[0],0.001f);}
    @Test public void leStlAscii() throws Exception {String stl="solid x\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid";assertEquals(1,loader.load("x.stl",stl.getBytes("UTF-8")).getMesh().getTriangleCount());}
    @Test(expected=ModelFormatException.class) public void rejeitaObjComIndiceInvalido() throws Exception {loader.load("x.obj","v 0 0 0\nf 1 2 3".getBytes("UTF-8"));}
    @Test public void gltfSemBufferLocalNaoFingeRenderizacao() throws Exception {String g="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"mesh.bin\",\"byteLength\":12}]}";ModelLoadResult r=loader.load("x.gltf",g.getBytes("UTF-8"));assertFalse(r.isRenderable());assertTrue(r.getMessage().contains("externo"));}
    @Test public void renderizaGltfAutocontidoComTriangulo() throws Exception {String g="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAAAA\",\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}]}";ModelLoadResult r=loader.load("x.gltf",g.getBytes("UTF-8"));assertTrue(r.isRenderable());assertEquals(1,r.getMesh().getTriangleCount());}
    @Test public void renderizaGlbComBufferBinarioReal() throws Exception {String json="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}]}";byte[] j=json.getBytes("UTF-8");int padded=(j.length+3)&~3;ByteBuffer glb=ByteBuffer.allocate(12+8+padded+8+36).order(ByteOrder.LITTLE_ENDIAN);glb.putInt(0x46546c67).putInt(2).putInt(glb.capacity()).putInt(padded).putInt(0x4e4f534a).put(j);while(glb.position()<20+padded)glb.put((byte)0x20);glb.putInt(36).putInt(0x004e4942).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0);ModelLoadResult r=loader.load("x.glb",glb.array());assertTrue(r.isRenderable());assertEquals(ModelFormat.GLB,r.getFormat());assertEquals(1,r.getMesh().getTriangleCount());}
    @Test(expected=ModelFormatException.class) public void rejeitaGltfInvalido() throws Exception {loader.load("x.gltf","{ruim".getBytes("UTF-8"));}
}
