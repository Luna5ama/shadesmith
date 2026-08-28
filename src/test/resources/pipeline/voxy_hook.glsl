layout(location = 0) out vec4 fragmentOutput;

void voxy_emitFragment(VoxyFragmentParameters parameters) {
    fragmentOutput = parameters.sampledColour;
}
