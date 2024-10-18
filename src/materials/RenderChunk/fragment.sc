$input v_color0, v_color1, v_fog, v_refl, v_texcoord0, v_lightmapUV, v_extra, v_worldPos, v_position

#include <bgfx_shader.sh>
#include <newb/main.sh>

uniform vec4 FogColor;
uniform vec4 ViewPositionAndTime;
uniform vec4 FogAndDistanceControl;

SAMPLER2D_AUTOREG(s_MatTexture);
SAMPLER2D_AUTOREG(s_SeasonsTexture);
SAMPLER2D_AUTOREG(s_LightMapTexture);

float fog_fade(vec3 wPos) {
  return clamp(2.0-length(wPos*vec3(0.005, 0.002, 0.005)), 0.0, 1.0);
}

void main() {
  vec4 diffuse;
  vec4 color;
  vec3 viewDir = normalize(v_worldPos);
  vec3 dir = normalize(cross(dFdx(v_position), dFdy(v_position)));
  float dx = max(dir.x, -dir.x);
  float dy = max(dir.y, -dir.y);
  float dz = max(dir.z, -dir.z);
    // environment detections
  bool end = detectEnd(FogColor.rgb, FogAndDistanceControl.xy);
  bool nether = detectNether(FogColor.rgb, FogAndDistanceControl.xy);
  bool underWater = detectUnderwater(FogColor.rgb, FogAndDistanceControl.xy);
  float rainFactor = detectRain(FogAndDistanceControl.xyz);

  // sky colors
  vec3 zenithCol;
  vec3 horizonCol;
  vec3 horizonEdgeCol;
  if (underWater) {
    vec3 fogcol = getUnderwaterCol(FogColor.rgb);
    zenithCol = fogcol;
    horizonCol = fogcol;
    horizonEdgeCol = fogcol;
  } else if (end) {
    zenithCol = getEndZenithCol();
    horizonCol = getEndHorizonCol();
    horizonEdgeCol = horizonCol;
  } else {
    vec3 fs = getSkyFactors(FogColor.rgb);
    zenithCol = getZenithCol(rainFactor, FogColor.rgb, fs);
    horizonCol = getHorizonCol(rainFactor, FogColor.rgb, fs);
    horizonEdgeCol = getHorizonEdgeCol(horizonCol, rainFactor, FogColor.rgb);
  }

  #if defined(DEPTH_ONLY_OPAQUE) || defined(DEPTH_ONLY)
    diffuse = vec4(1.0,1.0,1.0,1.0);
    color = vec4(1.0,1.0,1.0,1.0);
  #else
    diffuse = texture2D(s_MatTexture, v_texcoord0);

    #ifdef ALPHA_TEST
      if (diffuse.a < 0.6) {
        discard;
      }
    #endif

    #if defined(SEASONS) && (defined(OPAQUE) || defined(ALPHA_TEST))
      diffuse.rgb *= mix(vec3(1.0,1.0,1.0), texture2D(s_SeasonsTexture, v_color1.xy).rgb * 2.0, v_color1.z);
    #endif

    color = v_color0;
  #endif

  vec3 glow = nlGlow(s_MatTexture, v_texcoord0, v_extra.a);

  diffuse.rgb *= diffuse.rgb;

  vec3 lightTint = texture2D(s_LightMapTexture, v_lightmapUV).rgb;
  lightTint = mix(lightTint.bbb, lightTint*lightTint, 0.35 + 0.65*v_lightmapUV.y*v_lightmapUV.y*v_lightmapUV.y);

  color.rgb *= lightTint;
  
  float shadow = smoothstep(0.875,0.860, pow(v_lightmapUV.y,2.0));
  
  diffuse.rgb *= 1.0-0.4*shadow;
  diffuse.rgb *= 1.0-0.4*dx;

  #ifdef TRANSPARENT
    if (v_extra.b > 0.9) {
    
      diffuse.rgb = vec3_splat(1.0 - NL_WATER_TEX_OPACITY*(1.0 - diffuse.b*1.8));
      diffuse.a = color.a;
      
    }
  #else
    diffuse.a = 1.0;
  #endif

  diffuse.rgb *= color.rgb;
  diffuse.rgb += glow;

  if (v_extra.b > 0.9) {
    diffuse.rgb += v_refl.rgb*v_refl.a;
    vec3 normal = getWaterNormalMapFromHeight(mod(v_position.xz,16.0)+0.1*ViewPositionAndTime.w, vec2(20.0, 20.0), 1.2, 0.5*ViewPositionAndTime.w).xzy;
      vec3 cloudPos = v_worldPos;
      
      cloudPos.y = mix(cloudPos.y, -cloudPos.y, dy);
      
      viewDir.y = mix(viewDir.y, -viewDir.y, dy);
      
      viewDir = reflect(viewDir, normal);
      
      cloudPos.xz = 80.0*viewDir.xz/viewDir.y;
      
      cloudPos.xz = -cloudPos.xz;
      
      vec3 skyPos = viewDir;
      
      skyPos.xz = -skyPos.xz;
      
      
      vec3 sky = nlRenderSky(horizonEdgeCol, horizonCol, zenithCol, skyPos, FogColor.rgb, ViewPositionAndTime.w, rainFactor, end, underWater, nether);
      
      float skyBr = (sky.r+sky.g+sky.b)/3.0;
      
      diffuse.rgb = mix(diffuse.rgb, sky, skyBr);
      
      // Shooting star reflection on water
      #ifdef NL_SHOOTING_STAR_REFL
      diffuse.rgb += nlRenderShootingStar(viewDir, FogColor.rgb, ViewPositionAndTime.w);
      #endif
      
      // Clouds reflection on water
      #ifdef NL_ROUNDED_CLOUD_REFL
      
      #if NL_CLOUD_TYPE == 2
      vec4 clouds = renderClouds(viewDir, cloudPos, rainFactor, ViewPositionAndTime.w, horizonCol, zenithCol);
      
      float fade = fog_fade(v_worldPos);
      
      diffuse.rgb = mix(diffuse.rgb, clouds.rgb*1.2, clouds.a*fade);
      #endif
      
      #endif
      
      // Sun & Moon Reflection on water
      #ifdef NL_SUN_REFL
      vec3 sunPosition = v_worldPos;
      
      sunPosition.y = -sunPosition.y;
      
      sunPosition = reflect(sunPosition, normal);
      
      vec3 sun = renderSun(sunPosition, NL_FAKE_SUN_ROT, NL_FAKE_SUN_HEIGHT, NL_FAKE_SUN_SIZE, NL_FAKE_SUN_BRIGHTNESS);
      
      float sunBr = (sun.r+sun.g+sun.b)/3.0;
      
      diffuse.rgb += sun;
      #endif
      
      diffuse.rgb *= vec3(0.8,0.8,0.99);
      
  } else if (v_refl.a > 0.0) {
    // reflective effect - only on xz plane
    float dy = abs(dFdy(v_extra.g));
    if (dy < 0.0002) {
      float mask = v_refl.a*(clamp(v_extra.r*10.0,8.2,8.8)-7.8);
      diffuse.rgb *= 1.0 - 0.6*mask;
      diffuse.rgb += v_refl.rgb*mask;
    }
  }

  diffuse.rgb = mix(diffuse.rgb, v_fog.rgb, v_fog.a);

  diffuse.rgb = colorCorrection(diffuse.rgb);

  gl_FragColor = diffuse;
}
