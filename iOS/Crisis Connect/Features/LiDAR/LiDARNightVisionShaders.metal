#include <metal_stdlib>
using namespace metal;

struct LiDARVertexOut {
    float4 position [[position]];
    float2 viewportCoordinate;
};

vertex LiDARVertexOut lidarFullscreenVertex(uint vertexID [[vertex_id]]) {
    constexpr float2 positions[] = {
        float2(-1.0, -1.0),
        float2( 1.0, -1.0),
        float2(-1.0,  1.0),
        float2( 1.0,  1.0)
    };
    constexpr float2 coordinates[] = {
        float2(0.0, 1.0),
        float2(1.0, 1.0),
        float2(0.0, 0.0),
        float2(1.0, 0.0)
    };

    LiDARVertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    output.viewportCoordinate = coordinates[vertexID];
    return output;
}

fragment half4 lidarNightVisionFragment(
    LiDARVertexOut input [[stage_in]],
    texture2d<float, access::sample> cameraY [[texture(0)]],
    texture2d<float, access::sample> cameraCbCr [[texture(1)]],
    texture2d<float, access::read> depthMap [[texture(2)]],
    texture2d<uint, access::read> confidenceMap [[texture(3)]],
    constant float3x3 &viewportToImage [[buffer(0)]],
    constant float &maximumDepth [[buffer(1)]],
    constant uint &hasDepth [[buffer(2)]]) {
    float3 transformed = viewportToImage * float3(input.viewportCoordinate, 1.0);
    float2 imageCoordinate = transformed.xy / max(transformed.z, 0.0001);
    if (any(imageCoordinate < 0.0) || any(imageCoordinate > 1.0)) {
        return half4(0.0, 0.01, 0.0, 1.0);
    }

    constexpr sampler cameraSampler(
        coord::normalized,
        address::clamp_to_edge,
        filter::linear
    );
    float y = cameraY.sample(cameraSampler, imageCoordinate).r;
    float2 chroma = cameraCbCr.sample(cameraSampler, imageCoordinate).rg - float2(0.5);
    float3 cameraColor = float3(
        y + 1.4020 * chroma.y,
        y - 0.3441 * chroma.x - 0.7141 * chroma.y,
        y + 1.7720 * chroma.x
    );
    cameraColor = saturate(cameraColor);

    float luminance = dot(cameraColor, float3(0.2126, 0.7152, 0.0722));
    luminance = pow(saturate((luminance - 0.015) * 1.42), 0.82);
    float3 outputColor = float3(luminance * 0.16, luminance, luminance * 0.34);

    if (hasDepth != 0) {
        uint2 depthSize = uint2(depthMap.get_width(), depthMap.get_height());
        float2 clampedCoordinate = clamp(imageCoordinate, 0.0, 0.999999);
        uint2 depthCoordinate = min(uint2(clampedCoordinate * float2(depthSize)), depthSize - 1);
        uint2 confidenceSize = uint2(confidenceMap.get_width(), confidenceMap.get_height());
        uint2 confidenceCoordinate = min(
            uint2(clampedCoordinate * float2(confidenceSize)),
            confidenceSize - 1
        );
        uint confidence = confidenceMap.read(confidenceCoordinate).r;
        float depth = depthMap.read(depthCoordinate).r;

        if (confidence >= 1 && isfinite(depth) && depth > 0.0) {
            uint2 rightCoordinate = min(depthCoordinate + uint2(1, 0), depthSize - 1);
            uint2 downCoordinate = min(depthCoordinate + uint2(0, 1), depthSize - 1);
            float rightDepth = depthMap.read(rightCoordinate).r;
            float downDepth = depthMap.read(downCoordinate).r;
            float gradient = 0.0;
            if (isfinite(rightDepth) && rightDepth > 0.0) {
                gradient += abs(depth - rightDepth);
            }
            if (isfinite(downDepth) && downDepth > 0.0) {
                gradient += abs(depth - downDepth);
            }

            float normalizedDepth = saturate(depth / max(maximumDepth, 1.0));
            float proximity = pow(1.0 - normalizedDepth, 0.78);
            float edge = saturate(gradient / 0.45);
            float overlayStrength = proximity * 0.10 + edge * 0.40;
            if (depth < 1.1) {
                overlayStrength = max(overlayStrength, 0.48);
            } else if (depth < 2.2) {
                overlayStrength = max(overlayStrength, 0.24);
            }
            if (confidence == 1) {
                overlayStrength *= 0.68;
            }

            float3 depthTint = float3(0.20, 1.0, 0.45);
            outputColor = mix(outputColor, max(outputColor, depthTint), saturate(overlayStrength));
        }
    }

    return half4(half3(saturate(outputColor)), 1.0);
}
