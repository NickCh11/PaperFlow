import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import torch
from model import UVDocnet
model=UVDocnet(num_filter=32,kernel_size=5)
checkpoint=torch.load(Path(__file__).with_name('uvdoc-model.pkl'),map_location='cpu',weights_only=True)
model.load_state_dict(checkpoint['model_state'])
model.eval()
class Grid(torch.nn.Module):
    def __init__(self,model):
        super().__init__();self.model=model
    def forward(self,image):
        return self.model(image)[0]
output=Path(__file__).with_name('uvdoc.onnx')
torch.onnx.export(Grid(model),torch.zeros(1,3,712,488),str(output),opset_version=11,input_names=['image'],output_names=['grid'],do_constant_folding=True,dynamo=False)
print(str(output),output.stat().st_size)
