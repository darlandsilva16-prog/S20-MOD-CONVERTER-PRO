package com.fs20modconverterpro.viewer3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converte somente as formas binárias efetivamente referenciadas pela cena em triângulos OpenGL. */
public final class I3dMeshAssembler {
    public MeshData assemble(I3dAnalyzer.Report report, GiantsShapesDecoder.ShapesFile shapes) throws ModelFormatException {
        Map<Long, GiantsShapesDecoder.Shape> byId=new HashMap<>();for(GiantsShapesDecoder.Shape shape:shapes.shapes)byId.put(shape.id,shape);
        MeshData.Builder out=new MeshData.Builder();out.addWarning("Materiais I3D foram identificados no XML, mas suas propriedades visuais ainda não são aplicadas.");
        for(String texture:report.textureReferences)out.addTexture(texture);
        for(String texture:report.missingTextures)out.addWarning("Textura referenciada não encontrada no ZIP: "+texture);
        List<Long> ids=report.sceneShapeIds;
        int sourceVertices=0;
        for(long id:ids){GiantsShapesDecoder.Shape shape=byId.get(id);if(shape==null){out.addWarning("A forma da cena com shapeId="+id+" não existe no arquivo .i3d.shapes.");continue;}
            sourceVertices+=shape.positions.length;
            int groups=shape.subsets.isEmpty()?1:shape.subsets.size();
            for(int g=0;g<groups;g++){int first=shape.subsets.isEmpty()?0:shape.subsets.get(g).firstIndex;int count=shape.subsets.isEmpty()?shape.indices.length:shape.subsets.get(g).indexCount;int end=Math.min(shape.indices.length,first+count);for(int i=first;i+2<end;i+=3){int a=shape.indices[i],b=shape.indices[i+1],c=shape.indices[i+2];out.addTriangle(shape.positions[a],shape.positions[b],shape.positions[c],new float[]{0.78f,0.78f,0.78f,1f});}}
            if(shape.normals!=null)out.addWarning("Normais por vértice foram lidas, mas a malha GLES atual recalcula normais planas.");
            if(shape.uvSets>0)out.addWarning("Coordenadas UV foram lidas; mapas de textura ainda não são amostrados pelo renderizador.");
            if((shape.options&64)!=0)out.addWarning("A forma usa dados de esqueleto; animação/pesos não são aplicados.");
        }
        out.setSourceVertices(sourceVertices);
        if(ids.isEmpty())throw new ModelFormatException("A cena I3D não contém instâncias Shape com shapeId para associar à geometria.");
        out.addWarning("Transformações da hierarquia I3D e materiais ainda não são aplicados; geometria estática local renderizada.");
        return out.build();
    }
}
