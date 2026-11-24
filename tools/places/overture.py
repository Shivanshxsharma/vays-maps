"""Pulls every place in and around Delhi from the open Overture Maps dataset into one plain file for `tools places`.

    pip install pyarrow
    python tools/places/overture.py data-src/overture.tsv.gz

Data: Overture Maps Foundation places theme (Meta, Microsoft, Foursquare, AllThePlaces and others),
CDLA-Permissive-2.0 / Apache-2.0 / CC0. See data/README.md.
"""
import gzip, sys
import pyarrow.compute as pc
import pyarrow.dataset as ds
import pyarrow.fs as fs

BOX = (76.83, 28.40, 77.35, 28.89)  # same box as core/Geo.kt Delhi
ROOT = 'overturemaps-us-west-2/release/'

def clean(s):
    return ' '.join((s or '').replace('\t', ' ').split())

def main(out):
    s3 = fs.S3FileSystem(anonymous=True, region='us-west-2')
    release = sys.argv[2] if len(sys.argv) > 2 else max(f.base_name for f in s3.get_file_info(fs.FileSelector(ROOT)))
    d = ds.dataset(f'{ROOT}{release}/theme=places/type=place/', filesystem=s3, format='parquet')
    b = pc.field
    box = (b('bbox', 'xmin') > BOX[0]) & (b('bbox', 'xmax') < BOX[2]) & (b('bbox', 'ymin') > BOX[1]) & (b('bbox', 'ymax') < BOX[3])
    t = d.to_table(filter=box, columns=['names', 'confidence', 'basic_category', 'taxonomy', 'addresses', 'bbox', 'operating_status', 'sources'])
    n = 0
    with gzip.open(out, 'wt', encoding='utf-8', newline='\n') as f:
        f.write(f'# overture places {release}\n')
        for r in t.to_pylist():
            name = clean((r['names'] or {}).get('primary'))
            if not name or r['operating_status'] == 'permanently_closed':
                continue
            tax = r['taxonomy'] or {}
            a = (r['addresses'] or [{}])[0] or {}
            src = (r['sources'] or [{}])[0].get('dataset') or ''
            f.write('\t'.join([
                f"{r['bbox']['ymin']:.6f}", f"{r['bbox']['xmin']:.6f}", f"{r['confidence'] or 0:.3f}",
                (tax.get('hierarchy') or [''])[0] or '', r['basic_category'] or '', tax.get('primary') or '',
                src, clean(a.get('postcode')), clean(a.get('locality')), clean(a.get('freeform')), name]) + '\n')
            n += 1
    print(f'{n} places from Overture {release} -> {out}')

main(sys.argv[1])
