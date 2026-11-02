// Draws DCCon images into one numbered PNG so an AI agent can tag many at once.
//
//   sheet grid <out.png> <columns> <cell px> <image>...   numbered grid, middle frame of animated images
//   sheet frames <out.png> <columns> <cell px> <image>    up to 12 evenly spaced frames of one animated image
//
// Built by dct.py with swiftc; uses only ImageIO and CoreGraphics, so it runs on any Mac.

import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

let labelHeight = 30
let gap = 6
let background = CGColor(red: 0.91, green: 0.91, blue: 0.91, alpha: 1)

struct Tile {
    let image: CGImage?
    let label: String
}

func frames(of path: String) -> (source: CGImageSource?, count: Int) {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return (nil, 0) }
    return (source, CGImageSourceGetCount(source))
}

func drawLabel(_ text: String, in context: CGContext, x: Int, y: Int, width: Int) {
    let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 20, nil)
    let attributes: [NSAttributedString.Key: Any] = [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(red: 0.1, green: 0.1, blue: 0.1, alpha: 1),
    ]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
    let textWidth = CTLineGetTypographicBounds(line, nil, nil, nil)
    context.textPosition = CGPoint(x: Double(x) + (Double(width) - textWidth) / 2, y: Double(y) + 8)
    CTLineDraw(line, context)
}

func render(_ tiles: [Tile], columns: Int, cell: Int, to output: String) {
    let rows = (tiles.count + columns - 1) / columns
    let width = columns * cell + (columns + 1) * gap
    let height = rows * (cell + labelHeight) + (rows + 1) * gap
    guard let context = CGContext(
        data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { fatalError("Could not create a \(width)x\(height) canvas") }

    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.interpolationQuality = .high

    for (index, tile) in tiles.enumerated() {
        let column = index % columns, row = index / columns
        let x = gap + column * (cell + gap)
        // CoreGraphics counts y from the bottom; the first row goes at the top.
        let labelY = height - (gap + row * (cell + labelHeight + gap)) - cell - labelHeight
        let imageY = labelY + labelHeight

        context.setFillColor(background)
        context.fill(CGRect(x: x, y: imageY, width: cell, height: cell))
        if let image = tile.image {
            let scale = min(Double(cell) / Double(image.width), Double(cell) / Double(image.height))
            let w = Double(image.width) * scale, h = Double(image.height) * scale
            context.draw(image, in: CGRect(x: Double(x) + (Double(cell) - w) / 2, y: Double(imageY) + (Double(cell) - h) / 2, width: w, height: h))
        }
        drawLabel(tile.label, in: context, x: x, y: labelY, width: cell)
    }

    guard let image = context.makeImage(),
          let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: output) as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { fatalError("Could not write \(output)") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Could not write \(output)") }
}

let args = CommandLine.arguments
guard args.count >= 6, let columns = Int(args[3]), let cell = Int(args[4]) else {
    FileHandle.standardError.write("usage: sheet grid|frames <out.png> <columns> <cell px> <image>...\n".data(using: .utf8)!)
    exit(2)
}
let output = args[2]
let paths = Array(args[5...])

switch args[1] {
case "grid":
    let tiles = paths.enumerated().map { index, path -> Tile in
        let (source, count) = frames(of: path)
        let image = source.flatMap { CGImageSourceCreateImageAtIndex($0, count / 2, nil) }
        let label = image == nil ? "\(index + 1) (오류)" : count > 1 ? "\(index + 1) ▶\(count)" : "\(index + 1)"
        return Tile(image: image, label: label)
    }
    render(tiles, columns: columns, cell: cell, to: output)
case "frames":
    let (source, count) = frames(of: paths[0])
    guard let source, count > 0 else { fatalError("Could not read \(paths[0])") }
    let picks = count <= 12 ? Array(0..<count) : (0..<12).map { $0 * (count - 1) / 11 }
    let tiles = picks.map { Tile(image: CGImageSourceCreateImageAtIndex(source, $0, nil), label: "프레임 \($0 + 1)/\(count)") }
    render(tiles, columns: columns, cell: cell, to: output)
default:
    FileHandle.standardError.write("unknown mode \(args[1])\n".data(using: .utf8)!)
    exit(2)
}
