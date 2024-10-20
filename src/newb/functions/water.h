#ifndef WATER_H
#define WATER_H

#include "constants.h"
#include "sky.h"
#include "clouds.h"
#include "noise.h"

// fresnel - Schlick's approximation
float calculateFresnel(float cosR, float r0) {
  float a = 1.0-cosR;
  float a2 = a*a;
  return r0 + (1.0-r0)*a2*a2*a;
}

vec4 nlWater(
  inout vec3 wPos, inout vec4 color, vec4 COLOR, vec3 viewDir, vec3 light, vec3 cPos, vec3 tiledCpos,
  float fractCposY, vec3 FOG_COLOR, vec3 horizonCol, vec3 horizonEdgeCol, vec3 zenithCol,
  vec2 lit, highp float t, float camDist, float rainFactor,
  vec3 torchColor, bool end, bool nether, bool underWater
) {

  float cosR;
  float bump = NL_WATER_BUMP;
  vec3 waterRefl;

  if (fractCposY > 0.0) { // reflection for top plane
    // bump map
    #if NL_BUMP_TYPE == 1
    bump *= disp(tiledCpos, t) + 0.12*sin(t*2.0 + dot(cPos, vec3_splat(NL_CONST_PI_HALF)));
    #elif NL_BUMP_TYPE == 2
    bump *= noise2D(mod(cPos.xz, 16.0)+t);
    #elif NL_BUMP_TYPE == 3
    bump *= fbm2(mod(vec3(cPos.x, cPos.z, t), 16.0)*vec3(1.0,1.0,1.5));
    #endif
    
    // calculate cosine of incidence angle and apply water bump
    cosR = abs(viewDir.y);
    cosR = mix(cosR, 1.0-cosR*cosR, bump);
    viewDir.y = cosR;

    // sky reflection
    waterRefl = getSkyRefl(horizonEdgeCol, horizonCol, zenithCol, viewDir, FOG_COLOR, t, -wPos.y, rainFactor, end, underWater, nether);

    // cloud and aurora reflection
    #if defined(NL_WATER_CLOUD_REFLECTION)
      if (wPos.y < 0.0) {
        vec2 parallax = viewDir.xz/viewDir.y;
        vec2 projectedPos = wPos.xz - parallax*100.0*(1.0-bump);
        float fade = clamp(2.0 - 0.004*length(projectedPos), 0.0, 1.0);
        //projectedPos += fade*parallax;

        #ifdef NL_AURORA
          vec4 aurora = renderAurora(projectedPos.xyy, t, rainFactor, FOG_COLOR);
          waterRefl += 2.0*aurora.rgb*aurora.a*fade;
        #endif

        #if NL_CLOUD_TYPE == 1
          vec4 clouds = renderCloudsSimple(projectedPos.xyy, t, rainFactor, zenithCol, horizonCol, horizonEdgeCol);
          waterRefl = mix(waterRefl, 1.5*clouds.rgb, clouds.a*fade);
        #endif
      }
    #endif

    // torch light reflection
    waterRefl += torchColor*NL_TORCH_INTENSITY*(lit.x*lit.x + lit.x)*bump*10.0;

    #ifdef NL_WATER_REFL_MASK
    float mask = 0.15+0.08*sin(viewDir.x*12.0 + 31.4*bump);
    waterRefl *= 0.3+0.7*smoothstep(mask,0.2+mask,viewDir.y);
    #endif

    if (fractCposY>0.8 || fractCposY<0.9) { // flat plane
      waterRefl *= 1.0 - clamp(wPos.y, 0.0, 0.66);
    } else { // slanted plane and highly slanted plane
      waterRefl *= 0.1*sin(t*2.0+cPos.y*12.566) + (fractCposY > 0.9 ? 0.2 : 0.4);
    }
  } else { // reflection for side plane
    bump *= 0.5 + 0.5*sin(1.5*t + dot(cPos, vec3_splat(NL_CONST_PI_HALF)));

    cosR = max(sqrt(dot(viewDir.xz, viewDir.xz)), step(wPos.y, 0.5));
    cosR += (1.0-cosR*cosR)*bump;

    waterRefl = zenithCol;
  }

  // mask sky reflection under shade
  if (!end) {
    waterRefl *= 0.05 + lit.y*1.14;
  }

  float fresnel = calculateFresnel(cosR, 0.03);
  float opacity = 1.0-cosR;

  color.rgb *= 0.22*NL_WATER_TINT*(1.0-0.8*fresnel);

  #ifdef NL_WATER_FOG_FADE
    color.a *= NL_WATER_TRANSPARENCY;
  #else
    color.a = COLOR.a*NL_WATER_TRANSPARENCY;
  #endif

  color.a += (1.0-color.a)*opacity*opacity;

  #ifdef NL_WATER_WAVE
    if(camDist < 14.0) {
      wPos.y -= bump;
    }
  #endif

  return vec4(waterRefl, fresnel);
}

// Water Normal Map
float getWaterHeight(vec2 uv, float time) {
float bump = NL_WATER_BUMP;
vec2 cPos = uv;
cPos = mod(cPos, 16.0);
#if NL_BUMP_TYPE == 2
  bump *= noise2D(uv+time);
#elif NL_BUMP_TYPE == 3
  bump *= fbm2(vec3(uv.x, uv.y, time)*vec3(1.0,1.0,0.5));
#endif

return 1.0-bump;
}

vec4 getWaterNormalMapFromHeight(vec2 uv, vec2 resolution, float scale, float time) {
  vec2 step = 1.0 / resolution;

  float height = getWaterHeight(uv,time);

  vec2 dxy = height - vec2(
      getWaterHeight(uv + vec2(step.x, 0.0), time),
      getWaterHeight(uv + vec2(0.0, step.y), time)
  );
  return vec4(normalize(vec3(dxy * scale / step, 1.0)), height);
}


#endif
