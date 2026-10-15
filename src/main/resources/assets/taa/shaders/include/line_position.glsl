// Shared by every version. Clip the centerline before expanding in pixel space.
bool taaClipPlane(float startDistance, float endDistance, inout vec2 interval) {
    if (startDistance < 0.0 && endDistance < 0.0) return false;
    if (startDistance < 0.0 || endDistance < 0.0) {
        float crossing = startDistance / (startDistance - endDistance);
        if (startDistance < 0.0) interval.x = max(interval.x, crossing);
        else interval.y = min(interval.y, crossing);
    }
    return interval.x <= interval.y;
}

vec4 taaLinePosition(vec4 start, vec4 end, vec2 corner, float width, vec2 screenSize,
                     out float colorPosition) {
    vec2 interval = vec2(0.0, 1.0);
    vec2 extent = vec2(1.0) + (width + 4.0) / screenSize;
    bool visible = taaClipPlane(start.z + start.w, end.z + end.w, interval);
    visible = visible && taaClipPlane(start.w - 0.00001, end.w - 0.00001, interval);
    visible = visible && taaClipPlane(start.w - start.z, end.w - end.z, interval);
    visible = visible && taaClipPlane(start.x + extent.x * start.w, end.x + extent.x * end.w, interval);
    visible = visible && taaClipPlane(extent.x * start.w - start.x, extent.x * end.w - end.x, interval);
    visible = visible && taaClipPlane(start.y + extent.y * start.w, end.y + extent.y * end.w, interval);
    visible = visible && taaClipPlane(extent.y * start.w - start.y, extent.y * end.w - end.y, interval);
    colorPosition = 0.0;
    if (!visible) return vec4(2.0, 2.0, 2.0, 1.0);
    vec4 clippedStart = mix(start, end, interval.x), clippedEnd = mix(start, end, interval.y);
    if (clippedStart.w <= 0.0 || clippedEnd.w <= 0.0) return vec4(2.0, 2.0, 2.0, 1.0);
    vec2 direction = (clippedEnd.xy / clippedEnd.w - clippedStart.xy / clippedStart.w) * screenSize;
    float screenLength = length(direction);
    if (screenLength < 0.00001) return vec4(2.0, 2.0, 2.0, 1.0);
    vec2 offset = vec2(-direction.y, direction.x) / screenLength * width / screenSize;
    vec4 center = mix(clippedStart, clippedEnd, corner.x);
    colorPosition = mix(interval.x, interval.y, corner.x);
    return center + vec4(offset * corner.y * center.w, 0.0, 0.0);
}
