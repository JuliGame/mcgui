#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

#define HEIGHT_BIT 10
#define MAX_BIT 12
#define ADD_OFFSET 64
#define DEFAULT_OFFSET 0

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    vec3 pos = Position;
    vec2 ui = ceil(2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]));

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif

    // HUD glyphs carry their position in a huge ascent, which parks them far
    // below the screen. Bring them back and apply the state they were tagged with.
    if (ProjMat[3].x == -1.0 && pos.y >= ui.y) {
        int bits = int(pos.y) >> HEIGHT_BIT;
        if (((bits >> MAX_BIT) & 1) == 1) {
            int id = bits - (1 << MAX_BIT);
            pos.x -= 0.5 * ui.x;
            pos.y -= float((bits << HEIGHT_BIT) + ADD_OFFSET + DEFAULT_OFFSET);
            float xGui = 0.0;
            float yGui = 0.0;
            float layer = 0.0;
            switch (id) {
                case 0:
                    xGui = ui.x * 50.0 / 100.0;
                    break;
            }
            pos.x += xGui;
            pos.y += yGui;
            pos.z += layer;
        }
    }

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
#endif
    texCoord0 = UV0;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
}
